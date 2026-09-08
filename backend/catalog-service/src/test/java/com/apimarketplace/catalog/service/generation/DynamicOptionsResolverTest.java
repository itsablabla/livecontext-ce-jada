package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asking a provider what a parameter accepts.
 *
 * <p>Every rule here exists because getting it wrong costs money or lies to the
 * caller: calling a non-GET endpoint charges someone for opening a dropdown,
 * serving a cached list across a credential change offers ids the current key
 * never had, and collapsing "no key connected" into an empty list tells a reader
 * their account has no voices when in fact nobody was ever asked.
 */
class DynamicOptionsResolverTest {

    private static final UUID TOOL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID API = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ApiToolRepository tools;
    private ApiToolParameterRepository parameters;
    private ToolNameRepository toolNames;
    private CredentialClient credentials;
    private DynamicOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        tools = mock(ApiToolRepository.class);
        parameters = mock(ApiToolParameterRepository.class);
        toolNames = mock(ToolNameRepository.class);
        credentials = mock(CredentialClient.class);
        when(credentials.getCredentialStateVersion(anyString())).thenReturn("v1");
        resolver = new DynamicOptionsResolver(
                tools, parameters, toolNames, credentials, new ObjectMapper());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ApiToolParameterEntity param(String name, String extras) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setName(name);
        p.setExtras(extras);
        return p;
    }

    private static final String VOICE_EXTRAS = """
            {"valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";

    private static final UUID SOURCE_NAME_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    /**
     * Slugs here are PRODUCTION-shaped on purpose.
     *
     * <p>A stored tool_slug is derived: the api slug, a hyphen, then the
     * slugified endpoint name, plus a suffix when it collides. Fixtures that
     * used the bare seed name made every test pass against a lookup that could
     * never match a real row, which is how this reached an audit fully green
     * and completely inert.
     */
    private static ApiToolEntity tool(UUID id, String slug, String method, UUID toolNameId) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(id);
        t.setApiId(API);
        t.setToolSlug(slug);
        t.setMethod(method);
        t.setToolNameId(toolNameId == null ? null : toolNameId.toString());
        return t;
    }

    private static ToolNameEntity named(UUID id, String name) {
        ToolNameEntity n = new ToolNameEntity();
        n.setId(id);
        n.setName(name);
        return n;
    }

    /** The ordinary arrangement: a voice_id parameter whose source is a GET sibling. */
    private void wireVoiceEndpoint(String sourceMethod) {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-voices", sourceMethod, SOURCE_NAME_ID)));
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));
    }

    private static DynamicOptionsResolver.SourceFetcher answering(Object body) {
        return (id, source, credential) ->
                new DynamicOptionsResolver.SourceFetcher.Answer(true, body, "user", false);
    }

    private static Object twoVoices() {
        return Map.of("voices", List.of(
                Map.of("voice_id", "21m00", "name", "Rachel"),
                Map.of("voice_id", "AZnzlk", "name", "Domi")));
    }

    // ── reading the answer ──────────────────────────────────────────────────

    @Test
    @DisplayName("the values are read out of the declared array, labelled by the declared field")
    void readsValuesAndLabels() {
        wireVoiceEndpoint("GET");

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(twoVoices()));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).containsExactly(
                new DynamicOptionsResolver.Option("21m00", "Rachel"),
                new DynamicOptionsResolver.Option("AZnzlk", "Domi"));
        assertThat(r.truncated()).isFalse();
        assertThat(r.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an item with no label falls back to its value, which is still selectable")
    void labelFallsBackToValue() {
        wireVoiceEndpoint("GET");
        Object body = Map.of("voices", List.of(Map.of("voice_id", "21m00")));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(body));

        assertThat(r.options()).containsExactly(new DynamicOptionsResolver.Option("21m00", "21m00"));
    }

    @Test
    @DisplayName("an item missing the value field is dropped, and the others survive it")
    void skipsItemsWithoutAValue() {
        wireVoiceEndpoint("GET");
        // One malformed row must not cost the reader the whole list: the field
        // would fall back to free text over a single bad entry.
        Object body = Map.of("voices", new ArrayList<>(List.of(
                Map.of("name", "no id here"),
                Map.of("voice_id", "AZnzlk", "name", "Domi"))));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(body));

        assertThat(r.options()).containsExactly(new DynamicOptionsResolver.Option("AZnzlk", "Domi"));
    }

    @Test
    @DisplayName("regression: the execution envelope is looked through, so `items` is the endpoint's own path")
    void looksThroughTheExecutionEnvelope() {
        wireVoiceEndpoint("GET");
        // What the delegated execute path actually hands back: the endpoint's
        // answer wrapped under `result`, beside transport metadata. A descriptor
        // is written from the provider's documentation ("voices"), so without
        // this the fetch succeeds and silently resolves to nothing, which reads
        // to the caller as "this account has no voices".
        Object enveloped = Map.of(
                "success", true,
                "metadata", Map.of("credentialSource", "user"),
                "result", twoVoices());

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(enveloped));

        assertThat(r.options()).hasSize(2);
        assertThat(r.options().get(0).label()).isEqualTo("Rachel");
    }

    @Test
    @DisplayName("an endpoint that answers with the array itself needs no `items` path")
    void itemsMayBeOmitted() {
        String extras = """
                {"valuesFrom":{"tool":"list_voices","value":"voice_id","label":"name"}}""";
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", extras)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-voices", "GET", SOURCE_NAME_ID)));
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(),
                answering(List.of(Map.of("voice_id", "21m00", "name", "Rachel"))));

        assertThat(r.options()).containsExactly(new DynamicOptionsResolver.Option("21m00", "Rachel"));
    }

    @Test
    @DisplayName("an answer shaped differently than declared yields nothing rather than guesses")
    void wrongShapeYieldsNothing() {
        wireVoiceEndpoint("GET");

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(),
                answering(Map.of("voices", "not-an-array")));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).isEmpty();
    }

    @Test
    @DisplayName("past the ceiling the list is cut and SAYS it was, with the real total")
    void truncatesAndSaysSo() {
        wireVoiceEndpoint("GET");
        List<Object> many = new ArrayList<>();
        int total = DynamicOptionsResolver.MAX_OPTIONS + 7;
        for (int i = 0; i < total; i++) {
            many.add(Map.of("voice_id", "v" + i, "name", "Voice " + i));
        }

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(Map.of("voices", many)));

        // A silently cut list reads as exhaustive, and a caller concludes a
        // value they know exists does not.
        assertThat(r.options()).hasSize(DynamicOptionsResolver.MAX_OPTIONS);
        assertThat(r.truncated()).isTrue();
        assertThat(r.totalCount()).isEqualTo(total);
    }

    // ── the reasons there is nothing ────────────────────────────────────────

    @Nested
    @DisplayName("when there is nothing to offer, the REASON is named")
    class Unavailability {

        @Test
        @DisplayName("a parameter declaring no source is NOT_DYNAMIC, and nothing is called")
        void notDynamic() {
            when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", null)));
            AtomicInteger calls = new AtomicInteger();

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(),
                    (id, source, credential) -> {
                        calls.incrementAndGet();
                        return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
                    });

            assertThat(r.isAvailable()).isFalse();
            assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC);
            assertThat(calls).hasValue(0);
        }

        @Test
        @DisplayName("a missing key is NO_CREDENTIAL, not a failure: the fix is the reader's")
        void noCredential() {
            wireVoiceEndpoint("GET");

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(),
                    (id, source, credential) ->
                            new DynamicOptionsResolver.SourceFetcher.Answer(false, null, null, true));

            assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NO_CREDENTIAL);
        }

        @Test
        @DisplayName("a refusal with a key present is FETCH_FAILED")
        void fetchFailed() {
            wireVoiceEndpoint("GET");

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(),
                    (id, source, credential) ->
                            new DynamicOptionsResolver.SourceFetcher.Answer(false, null, null, false));

            assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.FETCH_FAILED);
        }

        @Test
        @DisplayName("a fetcher that throws is caught: opening a field cannot break the caller")
        void fetcherThrowing() {
            wireVoiceEndpoint("GET");

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(),
                    (id, source, credential) -> { throw new IllegalStateException("transport down"); });

            assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.FETCH_FAILED);
        }

        @Test
        @DisplayName("a source naming an endpoint that does not exist fails instead of calling something else")
        void unknownSourceEndpoint() {
            when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));
            when(tools.findById(TOOL)).thenReturn(
                    Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
            when(tools.findByApiId(API)).thenReturn(
                    List.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(twoVoices()));

            assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.FETCH_FAILED);
        }
    }

    // ── the two rules that cost money to get wrong ──────────────────────────

    @Test
    @DisplayName("a source that is not a GET is REFUSED: a dropdown must never spend anything")
    void refusesNonGetSource() {
        wireVoiceEndpoint("POST");
        AtomicInteger calls = new AtomicInteger();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(),
                (id, source, credential) -> {
                    calls.incrementAndGet();
                    return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
                });

        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.FETCH_FAILED);
        // The point is not the verdict, it is that the endpoint was never run.
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("a second ask on the same key is served from cache, costing no provider call")
    void cachesPerKey() {
        wireVoiceEndpoint("GET");
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);
        DynamicOptionsResolver.Resolution second =
                resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);

        assertThat(calls).hasValue(1);
        assertThat(second.options()).hasSize(2);
    }

    @Test
    @DisplayName("another payer is another question: the platform's list is not served from the user's")
    void cacheIsPerCredentialSource() {
        wireVoiceEndpoint("GET");
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), source, false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);
        resolver.resolve(TOOL, "voice_id", "u-1", "platform", null, Map.of(), counting);

        // Voices belong to the account behind the key. Sharing one entry would
        // offer the platform's ids to a run dispatched on the reader's own key.
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("another reader is another question: one account's voices are not served to another")
    void cacheIsPerUser() {
        wireVoiceEndpoint("GET");
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);
        resolver.resolve(TOOL, "voice_id", "u-2", "user", null, Map.of(), counting);

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("regression: editing a credential drops the entry instead of serving the gone key's list")
    void credentialChangeInvalidatesTheCache() {
        wireVoiceEndpoint("GET");
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);
        // What connecting, editing or deleting a key does to the account.
        when(credentials.getCredentialStateVersion("u-1")).thenReturn("v2");
        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("an unreachable auth-service keeps serving the last answer, deliberately")
    void authOutageFailsOpen() {
        wireVoiceEndpoint("GET");
        // What the client actually answers when it cannot reach auth-service: a
        // CONSTANT, never an exception. So the key stays stable and the last
        // answer serves out its five minutes. That is the platform's documented
        // trade (an outage must not disable caching), stated here so nobody
        // turns it into a per-call cache miss during an incident.
        when(credentials.getCredentialStateVersion("u-1"))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);
        resolver.resolve(TOOL, "voice_id", "u-1", "user", null, Map.of(), counting);

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("the answer names WHICH pool replied, not the pool that was asked for")
    void reportsThePoolThatActuallyReplied() {
        wireVoiceEndpoint("GET");

        // The run path can fall back from the caller's key to the platform's.
        // A list labelled with the request rather than the answer would let a
        // dialog show platform voices while claiming they are the reader's.
        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", null, null, Map.of(),
                (id, source, credential) ->
                        new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "platform", false));

        assertThat(r.credentialSource()).isEqualTo("platform");
    }

    // ── what a surface can ask without paying anything ──────────────────────

    @Test
    @DisplayName("a parameter's source can be read off the catalogue alone, calling nothing")
    void isDynamicReadsRowsOnly() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(
                param("voice_id", VOICE_EXTRAS),
                param("text", "{\"encoding\":\"json\"}")));

        assertThat(resolver.dynamicParameters(TOOL)).containsExactly("voice_id");
    }

    @Test
    @DisplayName("a nested parameter is advertised under the body path a descriptor writes, not only its name")
    void dynamicParametersAlsoAnswerToTheirBodyPath() {
        // HeyGen's avatar and voice belong to the connected account and are
        // fetched with the caller's key, but the descriptor writes them where
        // the provider wants them, inside video_inputs[0]. Advertising the name
        // alone left both looking like free text on the generation surface,
        // which for an opaque account-scoped id is a dead end.
        String extras = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"data.voices","value":"voice_id","label":"name"}}""";
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", extras)));

        // The nested place it fills, and ONLY that: the row declares its value
        // lands inside video_inputs, so a descriptor writing the flat name
        // 'voice_id' would fill a field this row never populates.
        assertThat(resolver.dynamicParameters(TOOL))
                .containsExactly("video_inputs[0].voice.voice_id");
    }

    @Test
    @DisplayName("the values of a nested parameter are fetched through the body path too")
    void resolvesByBodyPath() {
        // Advertising a parameter as fetchable and then answering nothing when
        // its values are asked for would be worse than never advertising it.
        String extras = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";
        wireVoiceEndpoint("GET");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", extras)));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "video_inputs[0].voice.voice_id", "u-1", "user", null, Map.of(),
                answering(twoVoices()));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).hasSize(2);
    }

    @Test
    @DisplayName("a row with a body path but NO source is not advertised as fetchable")
    void aBodyPathWithoutASourceIsNotAdvertised() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(
                param("text", "{\"bodyPath\":\"video_inputs[0].voice.input_text\"}")));

        assertThat(resolver.dynamicParameters(TOOL)).isEmpty();
    }

    @Test
    @DisplayName("a FLAT body path claim is not advertised: a flat path is a parameter's own name")
    void aFlatBodyPathClaimIsNotAdvertised() {
        // 'length_ms' claiming bodyPath 'duration' must not make 'duration'
        // answerable with length_ms's values. The catalogue's static lists are
        // matched under the same rule; the two halves have to agree or one of
        // them offers a parameter another parameter owns.
        String extras = """
                {"bodyPath":"duration",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";
        wireVoiceEndpoint("GET");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("length_ms", extras)));

        // Neither name is answerable, and that is the conservative half of the
        // rule: 'duration' is a flat path this row only CLAIMS, and 'length_ms'
        // is a name whose value the request does not write there. Offering
        // either would answer for a field with another one's values.
        assertThat(resolver.dynamicParameters(TOOL)).isEmpty();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "duration", "u-1", "user", null, Map.of(), answering(twoVoices()));

        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC);
    }

    @Test
    @DisplayName("a parameter's own NAME beats another row's claim on it, whatever order the rows arrive in")
    void theNameWinsOverAForeignClaim() {
        // The repository query has no ORDER BY. Tested both ways round so a
        // single-pass implementation testing name-or-bodyPath cannot pass.
        String claimant = """
                {"bodyPath":"voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"name","label":"voice_id"}}""";
        wireVoiceEndpoint("GET");
        for (List<ApiToolParameterEntity> order : List.of(
                List.of(param("legacy_voice", claimant), param("voice_id", VOICE_EXTRAS)),
                List.of(param("voice_id", VOICE_EXTRAS), param("legacy_voice", claimant)))) {
            when(parameters.findByApiToolId(TOOL)).thenReturn(order);

            DynamicOptionsResolver.Resolution r = resolver.resolve(
                    TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(twoVoices()));

            // The row NAMED voice_id reads value from voice_id and labels with
            // name; the claimant has them the other way round, so the values
            // themselves say which descriptor answered.
            assertThat(r.options()).first()
                    .isEqualTo(new DynamicOptionsResolver.Option("21m00", "Rachel"));
            // A fresh resolver per order: the answer is cached per account, and
            // the second arrangement must be read from the rows, not from the
            // first one's cache.
            resolver = new DynamicOptionsResolver(
                    tools, parameters, toolNames, credentials, new ObjectMapper());
        }
    }

    @Test
    @DisplayName("two rows claiming one nested path answer with nothing rather than with either list")
    void conflictingClaimsAnswerWithNothing() {
        String a = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";
        String b = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"name","label":"voice_id"}}""";
        wireVoiceEndpoint("GET");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_a", a), param("voice_b", b)));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "video_inputs[0].voice.voice_id", "u-1", "user", null, Map.of(),
                answering(twoVoices()));

        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC);
    }

    @Test
    @DisplayName("two rows claiming one path answer nothing, even when their sources agree")
    void duplicateClaimsAnswerNothing() {
        String same = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";
        wireVoiceEndpoint("GET");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("a", same), param("b", same)));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "video_inputs[0].voice.voice_id", "u-1", "user", null, Map.of(),
                answering(twoVoices()));

        // Agreeing today is not the same as being the owner: the seed still says
        // two rows fill one field, and which of them is authoritative is not a
        // question to answer by guessing. Same rule as the values half.
        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC);
    }

    @Test
    @DisplayName("a claimant with no source of its own still contests, and the field is not advertised")
    void aSourcelessClaimantContestsAndIsNotAdvertised() {
        // Advertising it and then refusing to answer is the one thing the
        // resolution path promises not to do: an agent is told the values can
        // be fetched and gets 'not fetchable' when it asks.
        String withSource = """
                {"bodyPath":"video_inputs[0].voice.voice_id",
                 "valuesFrom":{"tool":"list_voices","items":"voices","value":"voice_id","label":"name"}}""";
        String claimOnly = """
                {"bodyPath":"video_inputs[0].voice.voice_id"}""";
        wireVoiceEndpoint("GET");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(
                param("owner", claimOnly), param("other", withSource)));

        assertThat(resolver.dynamicParameters(TOOL))
                .doesNotContain("video_inputs[0].voice.voice_id");

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "video_inputs[0].voice.voice_id", "u-1", "user", null, Map.of(),
                answering(twoVoices()));

        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_DYNAMIC);
    }

    @Test
    @DisplayName("malformed extras leave the parameter plain instead of breaking the catalogue")
    void malformedExtrasAreIgnored() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(
                param("a", "not json at all"),
                param("b", "{\"valuesFrom\":\"a string, not an object\"}"),
                param("c", "{\"valuesFrom\":{\"tool\":\"list_voices\"}}")));

        // 'c' names a source but no value field, so nothing could be read from
        // the answer: a field that silently offers no values is worse than one
        // that was never advertised as having any.
        assertThat(resolver.dynamicParameters(TOOL)).isEmpty();
    }

    @Test
    @DisplayName("a parameter repository that fails leaves the field plain rather than erroring")
    void repositoryFailureIsSurvivable() {
        when(parameters.findByApiToolId(TOOL)).thenThrow(new IllegalStateException("db down"));

        assertThat(resolver.dynamicParameters(TOOL)).isEmpty();
    }

    @Test
    @DisplayName("regression: another KEY of the same account is another question")
    void cacheIsPerCredentialId() {
        wireVoiceEndpoint("GET");
        AtomicInteger calls = new AtomicInteger();
        DynamicOptionsResolver.SourceFetcher counting = (id, source, credential) -> {
            calls.incrementAndGet();
            return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
        };

        resolver.resolve(TOOL, "voice_id", "u-1", "user", "cred-A", Map.of(), counting);
        resolver.resolve(TOOL, "voice_id", "u-1", "user", "cred-B", Map.of(), counting);

        // A reader holding two keys for one provider switches between them in
        // the dialog. Sharing an entry hands key A's voices to a run dispatched
        // on key B, which the provider has never heard of and which fails after
        // the generation is paid for.
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a source is found by its NAME, whatever uniqueness suffix its slug carries")
    void findsTheSourceByNameNotBySlug() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        // What a real row looks like after a slug collision. Nothing a seed
        // author could write would ever equal this.
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-voices-2", "GET", SOURCE_NAME_ID)));
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(twoVoices()));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).hasSize(2);
    }

    @Test
    @DisplayName("regression: a same-named endpoint of ANOTHER api is not reachable")
    void neverReachesAnotherProvider() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        // Only this api's siblings are ever offered to the matcher, so the
        // other provider's identically-named endpoint is not among them.
        when(tools.findByApiId(API)).thenReturn(
                List.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        // The tool_names row IS shared across apis: 'list_voices' resolves to
        // the same name id for both, which is exactly why the api scope, and
        // not the name, has to be what keeps the reader's key at home.
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));
        AtomicInteger calls = new AtomicInteger();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(),
                (id, source, credential) -> {
                    calls.incrementAndGet();
                    return new DynamicOptionsResolver.SourceFetcher.Answer(true, twoVoices(), "user", false);
                });

        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.FETCH_FAILED);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("which parameters are dynamic is read once per endpoint, not once per model")
    void dynamicParametersAreCachedPerEndpoint() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));

        resolver.dynamicParameters(TOOL);
        resolver.dynamicParameters(TOOL);
        resolver.dynamicParameters(TOOL);

        // Both model listings ask this once per MODEL, and several models share
        // one endpoint: uncached, one shared snapshot became a query per model.
        org.mockito.Mockito.verify(parameters, org.mockito.Mockito.times(1)).findByApiToolId(TOOL);
    }

    @Test
    @DisplayName("the cache is KEYED per endpoint, so one endpoint's answer is not another's")
    void dynamicParametersAreKeyedPerEndpoint() {
        UUID other = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", VOICE_EXTRAS)));
        when(parameters.findByApiToolId(other)).thenReturn(List.of(param("text", "{}")));

        assertThat(resolver.dynamicParameters(TOOL)).containsExactly("voice_id");
        // A cache that held one entry for everyone would hand this endpoint the
        // first one's answer, and a surface would offer a dropdown on a field
        // that has none.
        assertThat(resolver.dynamicParameters(other)).isEmpty();
        assertThat(resolver.dynamicParameters(TOOL)).containsExactly("voice_id");
    }

    @Test
    @DisplayName("regression: a source name is looked up the way the IMPORT stored it")
    void sourceEndpointNameMatchesTheImportersOwnRule() {
        // A seed may name an endpoint 'ListStreams'; the import stores
        // 'list_streams'. 144 endpoints in the corpus are written that way, and
        // a descriptor pointing at one of them would miss the lookup, miss the
        // slug fallback too, and die silently: the same green-validator,
        // dead-dropdown failure that matching on the slug used to cause.
        String extras = """
                {"valuesFrom":{"tool":"ListVoices","items":"voices","value":"voice_id"}}""";
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", extras)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-voices", "GET", SOURCE_NAME_ID)));
        // Only the snake_case name exists in tool_names, which is what the
        // import wrote. The raw seed spelling is not there.
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(twoVoices()));

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).hasSize(2);
    }

    // ── values that belong to the MODEL, not to the account ─────────────────

    private static final String LANG_EXTRAS = """
            {"valuesFrom":{"tool":"list_models","match":{"field":"model_id","from":"model"},\
"items":"languages","value":"language_id","label":"name"}}""";

    /** A source that answers with every model at once, each carrying its own languages. */
    private static Object twoModels() {
        return List.of(
                Map.of("model_id", "eleven_v3", "languages", List.of(
                        Map.of("language_id", "en", "name", "English"),
                        Map.of("language_id", "ja", "name", "Japanese"))),
                Map.of("model_id", "eleven_flash_v2_5", "languages", List.of(
                        Map.of("language_id", "fr", "name", "French"))));
    }

    private void wireLanguageEndpoint() {
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("language_code", LANG_EXTRAS)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-models", "GET", SOURCE_NAME_ID)));
        when(toolNames.findByNameAndIsActiveTrue("list_models"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_models")));
    }

    @Test
    @DisplayName("reads the languages of the model being configured, not of every model")
    void selectsTheMatchingElement() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_v3"), answering(twoModels()));

        // Offering the UNION would let a caller pick a language this model
        // refuses, and the refusal arrives from the provider, after paying.
        assertThat(r.options()).containsExactly(
                new DynamicOptionsResolver.Option("en", "English"),
                new DynamicOptionsResolver.Option("ja", "Japanese"));
    }

    @Test
    @DisplayName("another model of the same endpoint gets its own languages")
    void selectionFollowsTheModel() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_flash_v2_5"), answering(twoModels()));

        assertThat(r.options()).containsExactly(new DynamicOptionsResolver.Option("fr", "French"));
    }

    /**
     * The endpoint's answer as it really arrives, for a source that answers with
     * an ARRAY at the root.
     *
     * <p>{@code ToolExecutionManager} merges {@code httpStatus} INTO the answer
     * when it is an object, and a root array has no object to merge into, so the
     * list is moved under {@code data} to carry the status beside it. Every
     * language dropdown went through this box and none of them was read.
     */
    private static Object rootArrayEnvelope(Object rows) {
        return Map.of(
                "success", true,
                "metadata", Map.of("credentialSource", "user"),
                "result", Map.of("data", rows, "httpStatus", Map.of("code", 200)));
    }

    @Test
    @DisplayName("regression: a source answering with a root ARRAY is read through the box execution puts it in")
    void readsThroughTheRootArrayEnvelope() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_v3"), answering(rootArrayEnvelope(twoModels())));

        // Before this was read, EVERY model of a root-array source was
        // unreachable, and the miss was reported as the provider not offering
        // the model to this key: a sentence that sent readers to change keys
        // for a defect that had nothing to do with their account.
        assertThat(r.isAvailable()).as("reason when unavailable: %s", r.unavailable()).isTrue();
        assertThat(r.options()).containsExactly(
                new DynamicOptionsResolver.Option("en", "English"),
                new DynamicOptionsResolver.Option("ja", "Japanese"));
    }

    @Test
    @DisplayName("regression: inside that box, a listed model with no languages is still a SUCCESS")
    void theReasonIsAlsoReadThroughTheRootArrayEnvelope() {
        wireLanguageEndpoint();
        Object empty = List.of(Map.of("model_id", "eleven_v3", "languages", List.of()));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_v3"), answering(rootArrayEnvelope(empty)));

        // The row IS in the box. A reason computed from a shallower look than
        // the read would call it unlisted, which is the same wrong sentence in
        // a case where nothing is wrong with the key at all.
        assertThat(r.isAvailable()).as("reason when unavailable: %s", r.unavailable()).isTrue();
        assertThat(r.options()).isEmpty();
    }

    @Test
    @DisplayName("regression: a model genuinely absent from the box is still reported as unlisted")
    void anAbsentModelInsideTheBoxIsStillUnlisted() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_unknown"), answering(rootArrayEnvelope(twoModels())));

        // Looking deeper must not turn the honest answer into a blank field:
        // the model really is not there, and that is the one thing the reader
        // can act on.
        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_LISTED);
    }

    @Test
    @DisplayName("a provider's OWN `data` field is read as the descriptor declares it, not unwrapped")
    void aProvidersOwnDataFieldIsNotMistakenForTheBox() {
        String extras = """
                {"valuesFrom":{"tool":"list_voices","items":"data","value":"voice_id","label":"name"}}""";
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("voice_id", extras)));
        when(tools.findById(TOOL)).thenReturn(
                Optional.of(tool(TOOL, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TOOL, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-voices", "GET", SOURCE_NAME_ID)));
        when(toolNames.findByNameAndIsActiveTrue("list_voices"))
                .thenReturn(List.of(named(SOURCE_NAME_ID, "list_voices")));

        // An object answer carries the status merged in, so it looks like the
        // box for a provider that names a field `data` itself. The documented
        // shape is tried first precisely so this keeps working.
        Object body = Map.of("success", true, "result", Map.of(
                "data", List.of(Map.of("voice_id", "21m00", "name", "Rachel")),
                "httpStatus", Map.of("code", 200)));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(), answering(body));

        assertThat(r.options()).containsExactly(new DynamicOptionsResolver.Option("21m00", "Rachel"));
    }

    @Test
    @DisplayName("a model the source does not list is NAMED as such, never silently empty")
    void anUnlistedModelIsReported() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_unknown"), answering(twoModels()));

        // Falling back to the first row would silently describe another model,
        // which is the failure a positional selector has by design. But an
        // empty answer is just as wrong the other way: it reads as "this model
        // has no languages" when the truth is "this key cannot see it", and a
        // blank field is what made the first live failure unreadable.
        assertThat(r.options()).isEmpty();
        assertThat(r.unavailable()).isEqualTo(DynamicOptionsResolver.Unavailable.NOT_LISTED);
    }

    @Test
    @DisplayName("a listed model with an empty list stays a SUCCESS: that account really has none")
    void aListedModelWithNothingIsNotAFailure() {
        wireLanguageEndpoint();
        Object empty = List.of(Map.of("model_id", "eleven_v3", "languages", List.of()));

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_v3"), answering(empty));

        // The row WAS found. "It offers none" and "it is not offered to you"
        // are different facts and must not collapse into one message.
        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).isEmpty();
    }

    @Test
    @DisplayName("a descriptor with no selector keeps its old empty-is-success meaning")
    void withoutASelectorAnEmptyAnswerStaysASuccess() {
        wireVoiceEndpoint("GET");

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "voice_id", "u-1", "user", null, Map.of(),
                answering(Map.of("voices", List.of())));

        // An account really can hold no voices; that is not a failure to report.
        assertThat(r.isAvailable()).isTrue();
        assertThat(r.options()).isEmpty();
    }

    @Test
    @DisplayName("no value to select on yields nothing rather than an arbitrary model's languages")
    void aMissingSelectorValueYieldsNothing() {
        wireLanguageEndpoint();

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null, Map.of(), answering(twoModels()));

        assertThat(r.options()).isEmpty();
    }

    @Test
    @DisplayName("the selection also looks through the execution envelope")
    void selectionWorksThroughTheEnvelope() {
        wireLanguageEndpoint();
        Object enveloped = Map.of(
                "success", true,
                "metadata", Map.of("credentialSource", "user"),
                "result", twoModels());

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TOOL, "language_code", "u-1", "user", null,
                Map.of("model", "eleven_v3"), answering(enveloped));

        assertThat(r.options()).hasSize(2);
    }

    @Test
    @DisplayName("a descriptor with half a selector is ignored, so nothing reads the wrong row")
    void halfASelectorIsRefused() {
        String broken = """
                {"valuesFrom":{"tool":"list_models","match":{"field":"model_id"},\
"items":"languages","value":"language_id"}}""";
        when(parameters.findByApiToolId(TOOL)).thenReturn(List.of(param("language_code", broken)));

        // Treated as no descriptor at all: the field keeps the free text it had.
        assertThat(resolver.dynamicParameters(TOOL)).isEmpty();
    }
}
