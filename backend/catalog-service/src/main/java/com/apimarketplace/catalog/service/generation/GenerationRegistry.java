package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.util.AllowedValuesParser;
import com.apimarketplace.catalog.util.ParameterBodyPath;
import com.apimarketplace.catalog.util.ParameterOwner;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves a public generation model id (e.g. {@code seedance-2.0-fast}) to
 * everything needed to call and bill it.
 *
 * <p>This is the piece that lets ONE tool and ONE workflow node cover every
 * format. The surfaces speak model ids; the registry turns a model id into the
 * catalog endpoint that executes it, the provider that owns the credential, and
 * the descriptor that says how to shape the request and where the asset lands.
 * Adding a provider therefore touches no surface at all.
 *
 * <p><b>Source of truth</b> - {@code catalog.api_tools.generation_spec}, seeded
 * from {@code scripts/api-migrations}. There is deliberately no second registry
 * to keep in sync: an admin toggling a model in the platform changes its
 * availability and its price, never its existence.
 *
 * <p><b>Caching</b> - the descriptor set changes only on a catalog import, so
 * it is read in one query and held for {@link #CACHE_TTL}. That keeps the
 * per-call resolution a map lookup instead of a database round trip on the hot
 * path of every generation.
 */
@Slf4j
@Service
public class GenerationRegistry {

    /** Short enough that a re-import is picked up without a restart. */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final ApiToolRepository apiToolRepository;
    private final ApiRepository apiRepository;
    private final ApiToolParameterRepository parameterRepository;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);

    public GenerationRegistry(ApiToolRepository apiToolRepository,
                               ApiRepository apiRepository,
                               ApiToolParameterRepository parameterRepository,
                               ObjectMapper objectMapper) {
        this.apiToolRepository = apiToolRepository;
        this.apiRepository = apiRepository;
        this.parameterRepository = parameterRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * One addressable generation model, resolved.
     *
     * @param modelId              public id the surfaces use
     * @param kind                 modality produced (image, video, voice, ...)
     * @param model                the descriptor entry for this model
     * @param spec                 the endpoint's full descriptor
     * @param apiToolId            catalog endpoint that submits the job, and the
     *                             key the published price is attached to
     * @param toolSlug             composite {@code apiSlug/toolSlug}, the form the
     *                             billing and execution layers already speak
     * @param apiSlug              owning API's slug
     * @param apiName              owning API's display name
     * @param iconSlug             owning API's icon, for the admin screens
     * @param platformCredentialName integration name of the platform key that
     *                             unlocks it, or null when the API has none
     */
    public record GenerationModel(
            String modelId,
            String kind,
            GenerationSpec.Model model,
            GenerationSpec spec,
            UUID apiToolId,
            String toolSlug,
            String apiSlug,
            String apiName,
            String iconSlug,
            String platformCredentialName,
            String executionMode,
            /**
             * Values the CATALOGUE knows a parameter accepts, keyed by unified
             * name, for the parameters this model's descriptor left
             * unrestricted. Display only: they never reach
             * {@link GenerationSpec.Constraint}, so nothing new is refused and
             * no billed default moves. See {@link GenerationLimits}.
             */
            Map<String, List<String>> catalogAllowed) {

        /**
         * A model with nothing inherited from the catalogue.
         *
         * <p>Kept so a caller that builds one by hand, and every test that
         * describes a model without caring where its lists came from, does not
         * have to name an empty map. Inheritance is an enrichment: its absence
         * is the ordinary case, not a special one.
         */
        public GenerationModel(String modelId, String kind, GenerationSpec.Model model,
                               GenerationSpec spec, UUID apiToolId, String toolSlug,
                               String apiSlug, String apiName, String iconSlug,
                               String platformCredentialName, String executionMode) {
            this(modelId, kind, model, spec, apiToolId, toolSlug, apiSlug, apiName,
                    iconSlug, platformCredentialName, executionMode, Map.of());
        }


        /**
         * True when the endpoint finishes the job asynchronously, which the
         * CATALOG owns through its execution block. Read from the endpoint
         * rather than from the descriptor so the two can never disagree about
         * whether a caller has to wait.
         */
        public boolean isAsync() {
            return "async_poll".equalsIgnoreCase(executionMode);
        }

        /** Label shown in admin screens and in the tool's discovery payload. */
        public String label() {
            return model.label();
        }

        /** Starting price shipped with the seed, before any platform override. */
        public GenerationSpec.Price seedPrice() {
            return model.price();
        }
    }

    /** Resolve one model by its public id. Case-insensitive. */
    public Optional<GenerationModel> resolve(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        return Optional.ofNullable(snapshot().byId.get(modelId.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Every model, optionally narrowed to one kind, ordered by kind then label
     * so the discovery payload and the admin tabs read the same way every time.
     */
    public List<GenerationModel> list(String kind) {
        List<GenerationModel> all = new ArrayList<>(snapshot().byId.values());
        if (kind != null && !kind.isBlank()) {
            String k = kind.trim().toLowerCase(Locale.ROOT);
            all.removeIf(m -> !m.kind().equals(k));
        }
        all.sort(Comparator.comparing(GenerationModel::kind).thenComparing(GenerationModel::label));
        return all;
    }

    /** Distinct kinds currently backed by at least one model. */
    public List<String> kinds() {
        return new ArrayList<>(new TreeSet<>(snapshot().byId.values().stream()
                .map(GenerationModel::kind).toList()));
    }

    /** Drop the cache so the next read reflects a just-finished import. */
    public void invalidate() {
        cache.set(null);
    }

    // ── snapshot building ───────────────────────────────────────────────────

    private Snapshot snapshot() {
        Snapshot current = cache.get();
        if (current != null && !current.isStale()) return current;
        Snapshot rebuilt = build();
        cache.set(rebuilt);
        return rebuilt;
    }

    private Snapshot build() {
        Map<String, GenerationModel> byId = new LinkedHashMap<>();
        List<ApiToolEntity> endpoints;
        try {
            endpoints = apiToolRepository.findGenerationEndpoints();
        } catch (Exception e) {
            log.error("[GenerationRegistry] failed to read generation endpoints: {}", e.getMessage(), e);
            return new Snapshot(Map.of(), Instant.now());
        }

        for (ApiToolEntity tool : endpoints) {
            String context = tool.getToolSlug() != null ? tool.getToolSlug() : String.valueOf(tool.getId());
            GenerationSpec spec;
            try {
                spec = GenerationSpec.parse(objectMapper.readTree(tool.getGenerationSpec()), context)
                        .orElse(null);
            } catch (Exception e) {
                // A row that fails to parse is skipped, never fatal: one bad
                // descriptor must not take the whole generation surface down.
                // The import is what refuses malformed descriptors; reaching
                // here means a row predates that gate or was written by hand.
                log.error("[GenerationRegistry] skipping unparseable generation_spec on tool {}: {}",
                        context, e.getMessage());
                continue;
            }
            if (spec == null) continue;

            ApiEntity api = apiRepository.findById(tool.getApiId()).orElse(null);
            if (api == null) {
                log.warn("[GenerationRegistry] tool {} has a generation spec but no owning API", context);
                continue;
            }
            String composite = api.getApiSlug() + "/" + tool.getToolSlug();
            Map<String, List<String>> catalogValues = catalogAllowedFor(tool, spec, composite);

            for (GenerationSpec.Model m : spec.models()) {
                if (!m.canAlwaysStateItsSize()) {
                    // The model still works for a caller who states the size,
                    // so it stays listed: deleting it here would turn a seed
                    // mistake into a missing product. But every call that omits
                    // the size is refused, which is worth saying once per
                    // snapshot rather than only in the caller's error.
                    log.warn("[GenerationRegistry] model '{}' on {} is priced per {} but neither "
                                    + "defaults nor requires '{}' - every call that omits it is refused. "
                                    + "Give its constraint an 'allowed' list of sizes, or list it as "
                                    + "required, in the seed.",
                            m.id(), composite, m.price().unit(), m.measuringParam());
                }
                GenerationModel resolved = new GenerationModel(
                        m.id(), spec.kind(), m, spec,
                        tool.getId(), composite,
                        api.getApiSlug(), api.getApiName(), api.getIconSlug(),
                        api.getPlatformCredentialName(), tool.getExecutionMode(),
                        // Only where the model states no list of its own: a
                        // descriptor that narrows the provider's set did so on
                        // purpose, and widening it back offers values this
                        // model refuses.
                        unrestrictedOnly(catalogValues, m));
                GenerationModel clash = byId.putIfAbsent(m.id(), resolved);
                if (clash != null) {
                    // Uniqueness is enforced per descriptor at import; a clash
                    // here means two DIFFERENT endpoints claimed the same id.
                    // First registration wins so behaviour stays deterministic,
                    // and the collision is logged loudly because it is a seed
                    // authoring bug that silently shadows a model.
                    log.error("[GenerationRegistry] duplicate generation model id '{}' claimed by {} and {}"
                                    + " - keeping {}. Fix the seed: model ids are global.",
                            m.id(), clash.toolSlug(), composite, clash.toolSlug());
                }
            }
        }

        log.info("[GenerationRegistry] {} generation model(s) across {} endpoint(s)",
                byId.size(), endpoints.size());
        return new Snapshot(Map.copyOf(byId), Instant.now());
    }

    /**
     * The values the CATALOGUE already knows each mapped parameter accepts.
     *
     * <p>The seed carries them: a provider that documents a closed set of
     * voices or formats has it on the endpoint parameter, and the importer
     * normalises the legacy scalar-array {@code default} into
     * {@code allowed_values}. The generation descriptor never read them, so a
     * dialog offered a free-text box for a required opaque identifier while the
     * admissible list sat one table away.
     *
     * <p>Read once per snapshot, so the extra query is paid every five minutes
     * rather than per request. A failure here is not fatal: the surface loses a
     * dropdown, not a model.
     */
    private Map<String, List<String>> catalogAllowedFor(ApiToolEntity tool, GenerationSpec spec, String context) {
        List<ApiToolParameterEntity> params;
        try {
            params = parameterRepository.findByApiToolId(tool.getId());
        } catch (Exception e) {
            log.warn("[GenerationRegistry] could not read parameters of {}: {} - its models keep "
                    + "free-text fields for anything the descriptor does not restrict", context, e.getMessage());
            return Map.of();
        }
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        spec.paramMap().forEach((unified, binding) -> {
            String path = binding.path();
            if (path == null) return;
            // A SCALED binding writes a different unit from the one the field is
            // labelled in: duration_seconds reaching music_length_ms multiplies
            // by 1000, so the parameter's own values are milliseconds and
            // offering them under a field that says seconds would suggest 30000
            // for a half-minute clip.
            if (binding.scale() != null) return;
            // WHICH row owns this write path is decided in one place, shared
            // with the resolver that fetches a parameter's values from the
            // provider. Deciding it twice is how a parameter came to be
            // described with one row's enumeration and another row's source.
            ParameterOwner.of(params, path)
                    .map(ApiToolParameterEntity::getAllowedValues)
                    .map(AllowedValuesParser::parseString)
                    .filter(values -> !values.isEmpty())
                    .ifPresent(values -> resolved.put(unified, values));
        });
        return resolved;
    }

    /** Drops anything the model already restricts itself. */
    private static Map<String, List<String>> unrestrictedOnly(Map<String, List<String>> catalogValues,
                                                              GenerationSpec.Model model) {
        if (catalogValues.isEmpty()) return Map.of();
        Map<String, List<String>> kept = new LinkedHashMap<>();
        catalogValues.forEach((unified, values) -> {
            // Only for parameters this model actually offers: a catalogue list
            // for something it does not accept would advertise a field that
            // never reaches the provider.
            if (!model.capabilities().contains(unified)) return;
            GenerationSpec.Constraint own = model.constraints().get(unified);
            if (own != null && !own.allowed().isEmpty()) return;
            kept.put(unified, values);
        });
        return kept.isEmpty() ? Map.of() : Map.copyOf(kept);
    }

    private record Snapshot(Map<String, GenerationModel> byId, Instant builtAt) {
        boolean isStale() {
            return Instant.now().isAfter(builtAt.plus(CACHE_TTL));
        }
    }
}
