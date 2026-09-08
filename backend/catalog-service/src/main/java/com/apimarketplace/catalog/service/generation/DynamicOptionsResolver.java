package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.util.ParameterBodyPath;
import com.apimarketplace.catalog.util.ParameterOwner;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The values a parameter accepts, when only the provider can say.
 *
 * <p>An ElevenLabs voice belongs to the account holding the key: the shared
 * defaults plus everything that account cloned or bought. It cannot be written
 * into a seed, and it must not be guessed, because the identifier is opaque and
 * a wrong one fails at the provider AFTER the call is charged. So it is fetched,
 * on demand, with the caller's own credential.
 *
 * <p><b>Fetched on demand, never while listing models.</b> The model list is one
 * snapshot shared by every reader and cached for five minutes; folding a
 * per-account list into it would fork that cache per user and pay one provider
 * call per model just to answer "what models exist".
 *
 * <p><b>The list and the run must agree about the key.</b> Voices differ per
 * credential, so a list fetched on one key and a run dispatched on another
 * offers an id the provider has never heard of. The fetch therefore goes through
 * the SAME delegated execute path as the run, carrying the same
 * {@code credential_source} and {@code credential_id}, and the answer says which
 * pool actually replied so a caller can see the two match.
 *
 * <p><b>The source endpoint has to be free to call.</b> A descriptor pointing at
 * a priced endpoint would charge a person for opening a dropdown, and an agent
 * can ask in a loop. Only a GET is accepted, and the answer is cached per
 * account and per credential so repeated asks cost one call.
 *
 * <p><b>Not every such value belongs to the account.</b> The languages a speech
 * model accepts belong to the MODEL, and the endpoint that knows them answers
 * with every model at once. A descriptor can therefore declare a
 * {@link DynamicOptions.Match}, which picks the one element the caller's
 * configuration is about before anything is read. Fetching those rather than
 * writing them into a seed is not about privacy but about truth: the provider's
 * own answer is the only list that stays correct when it adds a language, and it
 * is stated in whatever code system the provider actually accepts, which a
 * hand-copied list from its documentation is not.
 */
@Slf4j
@Service
public class DynamicOptionsResolver {

    /**
     * Long enough that opening a dialog twice, or an agent asking again mid
     * task, costs one provider call; short enough that a voice cloned a minute
     * ago shows up without anyone restarting anything.
     */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * A ceiling on what one fetch may return.
     *
     * <p>Not a display concern: it bounds what an answer can cost the caller
     * that asked for it, in tokens for an agent and in memory here. Past it the
     * count is reported, which is the part a caller can act on.
     */
    public static final int MAX_OPTIONS = 500;

    private final ApiToolRepository toolRepository;
    private final ApiToolParameterRepository parameterRepository;
    private final ToolNameRepository toolNameRepository;
    private final CredentialClient credentialClient;
    private final ObjectMapper objectMapper;

    private final Cache<String, List<Option>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * WHICH parameters declare a source, per endpoint. Catalogue rows, the same
     * thing for every reader, so this one is not keyed on anyone.
     */
    private final Cache<UUID, Set<String>> dynamicParameterCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public DynamicOptionsResolver(ApiToolRepository toolRepository,
                                   ApiToolParameterRepository parameterRepository,
                                   ToolNameRepository toolNameRepository,
                                   CredentialClient credentialClient,
                                   ObjectMapper objectMapper) {
        this.toolRepository = toolRepository;
        this.parameterRepository = parameterRepository;
        this.toolNameRepository = toolNameRepository;
        this.credentialClient = credentialClient;
        this.objectMapper = objectMapper;
    }

    /** One admissible value, and what a person should read instead of it. */
    public record Option(String value, String label) {}

    /**
     * What a caller is told when values could not be produced.
     *
     * <p>Named rather than folded into an empty list, because "this account has
     * no voices" and "you have not connected a key" call for opposite next
     * moves, and an empty list reads as the first.
     */
    public enum Unavailable {
        /** The parameter declares no source: it was never dynamic. */
        NOT_DYNAMIC,
        /** No key is connected for this API, so nobody can be asked. */
        NO_CREDENTIAL,
        /** The provider was asked and did not answer usefully. */
        FETCH_FAILED,
        /**
         * The provider answered, but lists nothing for the thing being
         * configured: the model is not among the ones this account can see.
         *
         * <p>Its own reason because it is the one empty result that is NOT
         * about the reader's account being empty. A model in limited release,
         * or one an account has no access to, is simply absent from the list,
         * and the honest answer is "this provider does not offer it to you",
         * not a blank field. That blank field is what made the first live
         * failure unreadable: it loaded, and then said nothing at all.
         */
        NOT_LISTED
    }

    /** Either the values, or the reason there are none. */
    public record Resolution(List<Option> options,
                             boolean truncated,
                             int totalCount,
                             String credentialSource,
                             Unavailable unavailable) {

        public boolean isAvailable() {
            return unavailable == null;
        }

        static Resolution unavailable(Unavailable reason) {
            return new Resolution(List.of(), false, 0, null, reason);
        }
    }

    /**
     * Every parameter of this endpoint that declares a source.
     *
     * <p>Cached, because the two model listings ask it once per MODEL and there
     * are more models than endpoints: forty-nine models over twenty-one
     * endpoints turned one shared, cached snapshot into forty-nine parameter
     * queries per reader. Keyed on the endpoint, so the models sharing one pay
     * for a single read. The rows change only on a catalogue import, which is
     * exactly what the model snapshot beside it already assumes.
     */
    public Set<String> dynamicParameters(UUID apiToolId) {
        Set<String> cached = dynamicParameterCache.getIfPresent(apiToolId);
        if (cached != null) return cached;

        // Both names a parameter answers to. A generation descriptor writes the
        // place the value lands in the body ('video_inputs[0].voice.voice_id'),
        // not the name the tool surface uses ('voice_id'), whenever the provider
        // wants it nested. Indexing only the name left those parameters looking
        // like free text on the generation surface, so an agent had no way to
        // learn the account's own avatar and voice ids and no error said why.
        //
        // A NESTED claim only, matching the rule the catalogue's own value lists
        // are matched under: a flat path is a parameter's name and nothing else,
        // and honouring a foreign row's flat claim would answer one parameter's
        // question with another parameter's values.
        // Every name a descriptor could write and find a source under: a row's
        // own name, and the place it claims to fill. Both go through the same
        // ownership rule as the values half, which is what decides a flat claim
        // and an ambiguity, so a key is advertised only when a single row owns
        // it AND that row declares a source. Advertising one nobody owns would
        // mark a parameter optionsAvailable and then answer "not fetchable" when
        // its values were asked for.
        List<ApiToolParameterEntity> rows = parameters(apiToolId);
        Set<String> candidates = new LinkedHashSet<>();
        for (ApiToolParameterEntity p : rows) {
            if (p.getName() != null) candidates.add(p.getName());
            String bodyPath = ParameterBodyPath.of(p.getExtras());
            if (bodyPath != null) candidates.add(bodyPath);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (ParameterOwner.of(rows, candidate).flatMap(this::parse).isPresent()) {
                names.add(candidate);
            }
        }
        Set<String> answer = Set.copyOf(names);
        dynamicParameterCache.put(apiToolId, answer);
        return answer;
    }

    /**
     * The UNIFIED parameter names of this model whose values can be fetched.
     *
     * <p>Answered from the catalogue rows alone, so a model list can name them
     * without paying a provider call per model: the call happens when someone
     * actually asks. Translating from the provider's own name ('voice_id') to
     * the unified one ('voice') here means no surface has to learn both.
     */
    public Set<String> unifiedDynamicParameters(GenerationRegistry.GenerationModel model) {
        Set<String> dynamic = dynamicParameters(model.apiToolId());
        if (dynamic.isEmpty()) return Set.of();
        Set<String> unified = new LinkedHashSet<>();
        model.spec().paramMap().forEach((name, binding) -> {
            if (!model.model().capabilities().contains(name)) return;
            // A SCALED binding writes a different unit from the one the field is
            // labelled in, so the provider's own values are not values for this
            // parameter: duration_seconds reaching music_length_ms would offer
            // 30000 for half a minute. The same guard the catalogue's static
            // lists are read under.
            if (binding.scale() != null) return;
            if (binding.path() != null && dynamic.contains(binding.path())) unified.add(name);
        });
        return unified;
    }

    /**
     * Ask the provider what this parameter accepts.
     *
     * @param fetcher      how to run the source endpoint; injected so the same
     *                     delegated path the RUN uses is the one used here, and
     *                     so this service does not depend on the tool layer
     * @param userId       whose credential is used, and whose cache entry this is
     * @param credentialSource {@code user} | {@code platform} | null for the
     *                     caller's default, which is the same resolution the run
     *                     performs
     */
    public Resolution resolve(UUID apiToolId,
                              String parameterName,
                              String userId,
                              String credentialSource,
                              String credentialId,
                              Map<String, String> knownValues,
                              SourceFetcher fetcher) {
        Optional<DynamicOptions> declared = descriptorFor(apiToolId, parameterName);
        if (declared.isEmpty()) return Resolution.unavailable(Unavailable.NOT_DYNAMIC);
        DynamicOptions options = declared.get();

        ApiToolEntity source = sourceEndpoint(apiToolId, options.tool());
        if (source == null) {
            log.warn("[DynamicOptions] {}#{} names source '{}' which does not exist in its api",
                    apiToolId, parameterName, options.tool());
            return Resolution.unavailable(Unavailable.FETCH_FAILED);
        }
        // A dropdown must not spend anything. Only a read is accepted, so a
        // descriptor cannot point at something that charges, mutates, or
        // consumes a quota by being opened.
        if (!"GET".equalsIgnoreCase(source.getMethod())) {
            log.error("[DynamicOptions] {}#{} names source '{}' which is {}, not GET - refusing to call it",
                    apiToolId, parameterName, options.tool(), source.getMethod());
            return Resolution.unavailable(Unavailable.FETCH_FAILED);
        }

        String key = cacheKey(apiToolId, parameterName, userId, credentialSource, credentialId);
        List<Option> cached = cache.getIfPresent(key);
        if (cached != null) {
            return shape(cached, credentialSource);
        }

        SourceFetcher.Answer answer;
        try {
            answer = fetcher.fetch(source.getId(), credentialSource, credentialId);
        } catch (Exception e) {
            log.warn("[DynamicOptions] fetching {} for {}#{} failed: {}",
                    options.tool(), apiToolId, parameterName, e.getMessage());
            return Resolution.unavailable(Unavailable.FETCH_FAILED);
        }
        if (answer == null || !answer.success()) {
            // A missing key is not a failure to report as one: it is the
            // ordinary state of an account that has not connected this provider,
            // and the fix belongs to the reader, not to us.
            return Resolution.unavailable(
                    answer != null && answer.credentialsMissing()
                            ? Unavailable.NO_CREDENTIAL
                            : Unavailable.FETCH_FAILED);
        }

        List<Option> read = walk(answer.body(), options, knownValues);
        // An empty answer has two very different meanings once a selector is in
        // play, and a blank field states the wrong one. Separated BEFORE the
        // result is cached, so the reason is what gets remembered too.
        if (read.isEmpty() && options.match() != null
                && !matched(answer.body(), options, knownValues)) {
            return Resolution.unavailable(Unavailable.NOT_LISTED);
        }
        if (read.isEmpty()) {
            // An account really can hold no voices. Cached like any other
            // answer: asking again immediately would cost a call to be told the
            // same thing.
            log.debug("[DynamicOptions] {}#{} resolved to no values", apiToolId, parameterName);
        }
        cache.put(key, read);
        return shape(read, answer.credentialSource() != null ? answer.credentialSource() : credentialSource);
    }

    /** Runs the source endpoint. Implemented by the layer that owns execution. */
    @FunctionalInterface
    public interface SourceFetcher {
        Answer fetch(UUID sourceToolId, String credentialSource, String credentialId);

        /**
         * @param body              the endpoint's answer, already projected
         * @param credentialSource  which pool actually replied, when known: the
         *                          only place that truth exists on a path that
         *                          falls back from the caller's key to the
         *                          platform's
         * @param credentialsMissing true when the call stopped because no key is
         *                          connected, which is a different message
         */
        record Answer(boolean success, Object body, String credentialSource, boolean credentialsMissing) {}
    }

    private Resolution shape(List<Option> all, String credentialSource) {
        boolean truncated = all.size() > MAX_OPTIONS;
        List<Option> shown = truncated ? new ArrayList<>(all.subList(0, MAX_OPTIONS)) : all;
        return new Resolution(shown, truncated, all.size(), credentialSource, null);
    }

    /**
     * Pull the values out of the endpoint's answer.
     *
     * <p>The answer has already been projected through the endpoint's declared
     * output schema, so the shape here is the documented one and this walks
     * known envelopes rather than guessing.
     */
    private List<Option> walk(Object body, DynamicOptions options, Map<String, String> knownValues) {
        for (Object candidate : envelopes(body)) {
            Object selected = select(candidate, options, knownValues);
            if (selected == null) continue;
            List<Option> read = read(selected, options);
            if (!read.isEmpty()) return read;
        }
        return List.of();
    }

    /**
     * The same answer, at each depth a platform envelope may have buried it.
     *
     * <p>A descriptor is written against the endpoint's DOCUMENTED shape, not
     * against the envelopes execution adds on the way here, so the shapes are
     * peeled here instead of being spelled out in every seed. Ordered
     * outermost-first, and the documented shape is always tried before a peel:
     * an endpoint that genuinely answers with a {@code result} or {@code data}
     * key of its own keeps working, because its own reading wins before the
     * unwrapping is reached.
     *
     * <p>Two of them, and the second one is why no language ever reached a
     * dropdown. {@code ToolExecutionManager} merges {@code httpStatus} INTO the
     * answer when it is an object, but an endpoint answering with an ARRAY at
     * the root has no object to merge into, so its list is moved under
     * {@code data} to carry that status. {@code list_voices} answers
     * {@code {voices:[...]}} and was unaffected; {@code list_models} answers
     * with a bare array and every one of its rows became unreachable, which
     * this class then reported as the provider not listing the model at all.
     */
    private List<Object> envelopes(Object body) {
        List<Object> candidates = new ArrayList<>(4);
        candidates.add(body);
        Object rootArray = arrayEnvelope(body);
        if (rootArray != null) candidates.add(rootArray);
        if (body instanceof Map<?, ?> map) {
            Object wrapped = map.get("result");
            if (wrapped != null) {
                candidates.add(wrapped);
                Object innerArray = arrayEnvelope(wrapped);
                if (innerArray != null) candidates.add(innerArray);
            }
        }
        return candidates;
    }

    /**
     * The list an endpoint answered with, when execution had to box it to carry
     * the HTTP status beside it. Null for anything that is not that box.
     *
     * <p>Both marks are required. {@code httpStatus} is what execution adds to
     * every answer, so its presence is what tells this box apart from a
     * provider's own {@code data} field; and the value has to BE a list,
     * because that is the only case the box exists for.
     */
    private static Object arrayEnvelope(Object body) {
        if (!(body instanceof Map<?, ?> map)) return null;
        if (!map.containsKey("httpStatus")) return null;
        Object data = map.get("data");
        return data instanceof List ? data : null;
    }

    /**
     * Narrow the answer to the one element the caller's own configuration is
     * about, before anything is read out of it.
     *
     * <p>Returns null when the descriptor asks for a selection and none can be
     * made. Null, not "the whole list": offering every model's languages would
     * let a caller pick one its model refuses, and the refusal arrives from the
     * provider after the call is paid for. Nothing is better than wrong here.
     */
    private Object select(Object body, DynamicOptions options, Map<String, String> knownValues) {
        DynamicOptions.Match match = options.match();
        if (match == null) return body;

        String wanted = knownValues == null ? null : knownValues.get(match.from());
        if (wanted == null || wanted.isBlank()) {
            log.warn("[DynamicOptions] descriptor selects on '{}' but the caller supplied no value for it",
                    match.from());
            return null;
        }
        if (!(body instanceof List<?> rows)) return null;
        for (Object row : rows) {
            if (row instanceof Map<?, ?> item && wanted.equals(scalar(item.get(match.field())))) {
                return item;
            }
        }
        log.debug("[DynamicOptions] no element of the source has {}={}", match.field(), wanted);
        return null;
    }

    /**
     * Whether the selector found its row at all, wherever the answer sits.
     *
     * <p>Asked separately from reading it, and only when the read came back
     * empty, so the ordinary path stays one pass. What it buys is the
     * difference between "that model lists no languages" and "that model is not
     * in the list", which are opposite things to tell someone.
     *
     * <p>It looks in the SAME places {@link #walk} does, from the same list, so
     * the two can never disagree about where the answer is. They did, and the
     * disagreement is what a reader ended up holding: a row that was simply out
     * of reach was reported as a model the provider does not offer them.
     */
    private boolean matched(Object body, DynamicOptions options, Map<String, String> knownValues) {
        for (Object candidate : envelopes(body)) {
            if (select(candidate, options, knownValues) != null) return true;
        }
        return false;
    }

    private List<Option> read(Object body, DynamicOptions options) {
        Object array = body;
        if (options.items() != null) {
            for (String segment : options.items().split("\\.")) {
                if (!(array instanceof Map<?, ?> map)) { array = null; break; }
                array = map.get(segment);
            }
        }
        if (!(array instanceof List<?> items)) return List.of();

        List<Option> out = new ArrayList<>();
        for (Object element : items) {
            if (!(element instanceof Map<?, ?> item)) continue;
            String value = scalar(item.get(options.value()));
            if (value == null) continue;
            String label = options.label() == null ? null : scalar(item.get(options.label()));
            out.add(new Option(value, label == null ? value : label));
        }
        return out;
    }

    /** A usable text value, or null for anything that is not one. */
    private static String scalar(Object node) {
        if (node == null || node instanceof Map || node instanceof List) return null;
        String text = String.valueOf(node).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The same normalisation the import applies to an endpoint's name before
     * storing it, so a descriptor is looked up under the name that was written.
     *
     * <p>Kept as a copy rather than shared: the importer is a separate artefact
     * that catalog-service does not depend on, and the alternative is a new
     * shared module for one string rule. If either side changes,
     * {@code sourceEndpointNameMatchesTheImportersOwnRule} fails.
     */
    private static String toSnakeCase(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    /**
     * The source declared by the parameter this write path addresses.
     *
     * <p>Every row is tried by NAME before any row is tried by the
     * {@code bodyPath} it claims, so a descriptor that writes a nested place
     * finds the same row {@link #dynamicParameters} advertised, and a name
     * always beats another row's claim on it. Matching only the name would
     * advertise a parameter as fetchable and then answer nothing when the
     * values were asked for.
     */
    private Optional<DynamicOptions> descriptorFor(UUID apiToolId, String parameterName) {
        if (parameterName == null) return Optional.empty();
        return ParameterOwner.of(parameters(apiToolId), parameterName).flatMap(this::parse);
    }

    private Optional<DynamicOptions> parse(ApiToolParameterEntity parameter) {
        String extras = parameter.getExtras();
        if (extras == null || extras.isBlank()) return Optional.empty();
        try {
            return DynamicOptions.from(objectMapper.readTree(extras));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<ApiToolParameterEntity> parameters(UUID apiToolId) {
        try {
            return parameterRepository.findByApiToolId(apiToolId);
        } catch (Exception e) {
            log.warn("[DynamicOptions] could not read parameters of {}: {}", apiToolId, e.getMessage());
            return List.of();
        }
    }

    /**
     * The sibling endpoint named by the descriptor, within the same API.
     *
     * <p><b>Matched on the endpoint's NAME, not its slug.</b> A seed names its
     * endpoints ({@code list_voices}) and that is what a descriptor can refer to
     * and what the seed validator can check. The stored {@code tool_slug} is a
     * DERIVED, api-scoped, uniqueness-suffixed string
     * ({@code elevenlabs-list-voices}, and {@code -2} if it collided), so no
     * value a seed author could write would ever equal it. Matching on it made
     * every lookup miss, and a miss here is silent: the field just falls back to
     * free text, which is indistinguishable from a parameter that never declared
     * a source.
     *
     * <p>The slug is still accepted as a second chance, for an install whose
     * {@code tool_names} row has drifted from the seed.
     */
    private ApiToolEntity sourceEndpoint(UUID apiToolId, String toolName) {
        ApiToolEntity self = toolRepository.findById(apiToolId).orElse(null);
        if (self == null) return null;
        // Scoped to the owning API on purpose: a descriptor must not be able to
        // make one provider's parameter call another provider's endpoint, which
        // would send the reader's key somewhere it does not belong.
        List<ApiToolEntity> siblings = toolRepository.findByApiId(self.getApiId());

        Set<String> nameIds = new LinkedHashSet<>();
        try {
            // Snake-cased, because the IMPORT snake-cases an endpoint's name
            // before storing it. Most seeds are written that way already, so
            // this is usually a no-op; the 144 corpus endpoints that are not
            // (`ListStreams`) would otherwise miss the lookup, fall through to
            // the slug, miss again, and die silently. That is the same
            // green-validator/dead-dropdown failure this lookup replaced, and
            // it costs one call to keep it from coming back.
            String stored = toSnakeCase(toolName);
            for (ToolNameEntity named : toolNameRepository.findByNameAndIsActiveTrue(stored)) {
                if (named.getId() != null) nameIds.add(named.getId().toString());
            }
        } catch (Exception e) {
            log.warn("[DynamicOptions] could not resolve source name '{}': {}", toolName, e.getMessage());
        }
        for (ApiToolEntity sibling : siblings) {
            if (sibling.getToolNameId() != null && nameIds.contains(sibling.getToolNameId())) {
                return sibling;
            }
        }
        for (ApiToolEntity sibling : siblings) {
            if (toolName.equals(sibling.getToolSlug())) return sibling;
        }
        return null;
    }

    /**
     * Per account AND per credential, because the answer is.
     *
     * <p>The credential STATE version is part of the key so connecting, editing
     * or deleting a key drops the entry instead of serving a list from the key
     * that is gone. That trap has been paid for once already, on the tool
     * response cache.
     */
    private String cacheKey(UUID apiToolId, String parameterName, String userId,
                            String credentialSource, String credentialId) {
        // Fail-open by design, and not this class's decision to make: the client
        // answers the constant STATE_VERSION_UNAVAILABLE rather than throwing
        // when auth-service cannot be reached, so an outage leaves this key
        // stable and the last answer serves out its five minutes. That is the
        // same trade the tool-response cache already makes deliberately (an
        // outage must not disable caching), and a stale voice list is a cheaper
        // failure than the one it buys.
        String version = credentialClient.getCredentialStateVersion(userId);
        return String.join("|", String.valueOf(apiToolId), parameterName, String.valueOf(userId),
                String.valueOf(credentialSource), String.valueOf(credentialId), version);
    }
}
