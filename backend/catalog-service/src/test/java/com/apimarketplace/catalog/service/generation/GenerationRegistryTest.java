package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import org.springframework.data.jdbc.repository.query.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry decides which endpoint a model id resolves to, and therefore
 * which price is charged. It had no test file at all, which is how the
 * non-deterministic duplicate resolution below survived four audits.
 *
 * <p>The cases here are the ones the class writes a comment to justify and then
 * never exercises: the cache and its staleness, a duplicate model id, a row that
 * cannot be parsed, an endpoint whose API is gone, and a read that fails.
 */
class GenerationRegistryTest {

    private static final String VIDEO_SPEC = """
            {"kind":"video","modelParam":"model","assetPath":"content.video_url",
             "paramMap":{"prompt":"content[0].text","duration_seconds":"duration"},
             "models":[{"id":"vid-1","upstream":"vendor-1","capabilities":["prompt","duration_seconds"],
                        "constraints":{"duration_seconds":{"allowed":[5,10]}},
                        "price":{"unit":"second","unitCredits":60}}]}
            """;

    private static final String VOICE_SPEC = """
            {"kind":"voice","assetPath":"$binary",
             "paramMap":{"prompt":"text"},
             "models":[{"id":"tts-1","capabilities":["prompt"],
                        "price":{"unit":"character","unitCredits":0.2}}]}
            """;

    private ApiToolRepository toolRepo;
    private ApiRepository apiRepo;
    private ApiToolParameterRepository paramRepo;
    private GenerationRegistry registry;

    @BeforeEach
    void setUp() {
        toolRepo = mock(ApiToolRepository.class);
        apiRepo = mock(ApiRepository.class);
        // Answers with no parameters by default: inheriting a catalogue list is
        // an enrichment, and every test here is about resolving models.
        paramRepo = mock(ApiToolParameterRepository.class);
        when(paramRepo.findByApiToolId(any())).thenReturn(List.of());
        registry = new GenerationRegistry(toolRepo, apiRepo, paramRepo, new ObjectMapper());
    }

    private ApiToolEntity tool(UUID apiId, String slug, String spec) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setApiId(apiId);
        t.setToolSlug(slug);
        t.setExecutionMode("sync");
        t.setGenerationSpec(spec);
        return t;
    }

    private UUID givenApi(String slug, String name) {
        UUID id = UUID.randomUUID();
        ApiEntity api = new ApiEntity();
        api.setId(id);
        api.setApiSlug(slug);
        api.setApiName(name);
        api.setPlatformCredentialName(slug + "_key");
        when(apiRepo.findById(id)).thenReturn(Optional.of(api));
        return id;
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("a model id resolves to the endpoint that runs it and the provider that owns the key")
        void resolvesToEndpointAndProvider() {
            UUID apiId = givenApi("seedance", "Seedance");
            ApiToolEntity t = tool(apiId, "create-video-task", VIDEO_SPEC);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));

            GenerationRegistry.GenerationModel m = registry.resolve("vid-1").orElseThrow();

            assertThat(m.apiToolId()).isEqualTo(t.getId());
            assertThat(m.toolSlug()).isEqualTo("seedance/create-video-task");
            assertThat(m.apiName()).isEqualTo("Seedance");
            assertThat(m.platformCredentialName()).isEqualTo("seedance_key");
            assertThat(m.kind()).isEqualTo("video");
            assertThat(m.seedPrice().perUnit()).isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("casing and padding never cause a miss, since an agent types the id")
        void lookupIsForgiving() {
            UUID apiId = givenApi("seedance", "Seedance");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(tool(apiId, "t", VIDEO_SPEC)));

            assertThat(registry.resolve("  VID-1  ")).isPresent();
            assertThat(registry.resolve(null)).isEmpty();
            assertThat(registry.resolve("  ")).isEmpty();
            assertThat(registry.resolve("no-such-model")).isEmpty();
        }

        @Test
        @DisplayName("async is read from the ENDPOINT, so the descriptor cannot disagree about waiting")
        void asyncComesFromTheEndpoint() {
            UUID apiId = givenApi("seedance", "Seedance");
            ApiToolEntity t = tool(apiId, "t", VIDEO_SPEC);
            t.setExecutionMode("async_poll");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));

            assertThat(registry.resolve("vid-1").orElseThrow().isAsync()).isTrue();
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("lists every model, and narrows to one kind")
        void listsAndFilters() {
            UUID videoApi = givenApi("seedance", "Seedance");
            UUID voiceApi = givenApi("elevenlabs", "ElevenLabs");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(
                    tool(videoApi, "v", VIDEO_SPEC), tool(voiceApi, "s", VOICE_SPEC)));

            assertThat(registry.list(null)).hasSize(2);
            assertThat(registry.list("video")).singleElement()
                    .satisfies(m -> assertThat(m.modelId()).isEqualTo("vid-1"));
            assertThat(registry.list("VOICE")).hasSize(1);
            assertThat(registry.list("hologram")).isEmpty();
            assertThat(registry.kinds()).containsExactly("video", "voice");
        }
    }

    @Nested
    @DisplayName("degradation")
    class Degradation {

        @Test
        @DisplayName("one unparseable row is skipped, it does not take the whole surface down")
        void unparseableRowIsSkipped() {
            UUID apiId = givenApi("seedance", "Seedance");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(
                    tool(apiId, "broken", "{\"kind\":\"video\"}"),   // no assetPath, no models
                    tool(apiId, "good", VIDEO_SPEC)));

            assertThat(registry.resolve("vid-1")).isPresent();
            assertThat(registry.list(null)).hasSize(1);
        }

        @Test
        @DisplayName("an endpoint whose owning API is gone is skipped rather than resolving half-built")
        void orphanEndpointIsSkipped() {
            UUID missing = UUID.randomUUID();
            when(apiRepo.findById(missing)).thenReturn(Optional.empty());
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(tool(missing, "t", VIDEO_SPEC)));

            assertThat(registry.list(null)).isEmpty();
            assertThat(registry.resolve("vid-1")).isEmpty();
        }

        @Test
        @DisplayName("a failed read yields an empty registry, never an exception on the call path")
        void failedReadDegradesQuietly() {
            when(toolRepo.findGenerationEndpoints()).thenThrow(new RuntimeException("db down"));

            assertThat(registry.list(null)).isEmpty();
            assertThat(registry.resolve("vid-1")).isEmpty();
            assertThat(registry.kinds()).isEmpty();
        }

        @Test
        @DisplayName("a duplicate model id keeps the FIRST endpoint, and the query orders so that is stable")
        void duplicateIdKeepsTheFirst() {
            // Model ids are global. Two providers claiming one id is a seed bug,
            // but the resolution must still be the same after a restart, or the
            // same call bills a different rate on a different day. The ORDER BY
            // in findGenerationEndpoints is what makes "first" mean something;
            // this pins the keep-the-first half.
            UUID apiA = givenApi("alpha", "Alpha");
            UUID apiB = givenApi("beta", "Beta");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(
                    tool(apiA, "first", VIDEO_SPEC), tool(apiB, "second", VIDEO_SPEC)));

            assertThat(registry.resolve("vid-1").orElseThrow().apiName()).isEqualTo("Alpha");
            assertThat(registry.list(null)).hasSize(1);
        }

        @Test
        @DisplayName("and the query that feeds it is ORDERED, which is the other half of that promise")
        void theFeedingQueryIsOrdered() throws NoSuchMethodException {
            // The test above pins "keep the first". First of WHAT is decided by
            // the database, and an unordered query is free to return the two
            // clashing rows in either order: the same call would then bill one
            // provider's rate before a restart and the other's after, with
            // nothing failing and no log line to explain it.
            //
            // Read off the annotation rather than run it, because ordering
            // cannot be observed without a database, and a guard that only runs
            // when Postgres happens to be up is not a guard. It fails the moment
            // somebody deletes the clause, which is the whole point.
            Query query = ApiToolRepository.class
                    .getMethod("findGenerationEndpoints")
                    .getAnnotation(Query.class);

            assertThat(query).as("findGenerationEndpoints must carry its own query").isNotNull();
            assertThat(query.value().toUpperCase(java.util.Locale.ROOT))
                    .as("an unordered feed makes duplicate-id resolution restart-dependent")
                    .contains("ORDER BY");
        }
    }

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        @DisplayName("the endpoints are read once and reused, so resolution is not a query per call")
        void readsOnceAndCaches() {
            UUID apiId = givenApi("seedance", "Seedance");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(tool(apiId, "t", VIDEO_SPEC)));

            registry.resolve("vid-1");
            registry.resolve("vid-1");
            registry.list(null);
            registry.kinds();

            verify(toolRepo, times(1)).findGenerationEndpoints();
        }

        @Test
        @DisplayName("invalidate forces the next read, which is what makes a re-import visible")
        void invalidateForcesAReread() {
            UUID apiId = givenApi("seedance", "Seedance");
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(tool(apiId, "t", VIDEO_SPEC)));
            registry.resolve("vid-1");

            registry.invalidate();
            registry.resolve("vid-1");

            verify(toolRepo, times(2)).findGenerationEndpoints();
        }

        @Test
        @DisplayName("a re-import that ADDS a model is picked up after invalidation")
        void invalidateSeesNewModels() {
            UUID videoApi = givenApi("seedance", "Seedance");
            UUID voiceApi = givenApi("elevenlabs", "ElevenLabs");
            when(toolRepo.findGenerationEndpoints())
                    .thenReturn(List.of(tool(videoApi, "v", VIDEO_SPEC)))
                    .thenReturn(List.of(tool(videoApi, "v", VIDEO_SPEC), tool(voiceApi, "s", VOICE_SPEC)));

            assertThat(registry.resolve("tts-1")).isEmpty();
            registry.invalidate();
            assertThat(registry.resolve("tts-1")).isPresent();
        }
    }

    @Nested
    @DisplayName("Values inherited from the catalogue")
    class CatalogAllowedValues {

        private ApiToolParameterEntity param(String name, String allowedValuesJson) {
            ApiToolParameterEntity p = new ApiToolParameterEntity();
            p.setName(name);
            p.setAllowedValues(allowedValuesJson);
            return p;
        }

        private ApiToolParameterEntity nestedParam(String name, String bodyPath, String allowedValuesJson) {
            ApiToolParameterEntity p = param(name, allowedValuesJson);
            p.setExtras("{\"bodyPath\":\"" + bodyPath + "\"}");
            return p;
        }

        /** A descriptor whose `voice` writes to the endpoint parameter `model`. */
        private static final String MAPPED_VOICE_SPEC = """
                {"kind":"voice","assetPath":"$binary",
                 "paramMap":{"prompt":"text","voice":"model"},
                 "models":[{"id":"aura","capabilities":["prompt","voice"],
                            "price":{"unit":"character","unitCredits":0.2}}]}
                """;

        @Test
        @DisplayName("a mapped parameter picks up the values the catalogue already knows")
        void inheritsCatalogAllowedValues() {
            // The seed documents the provider's voices on the endpoint parameter,
            // and the importer normalises them into allowed_values. Before this,
            // the dialog still drew a free-text box for a required opaque id while
            // the list sat one table away.
            UUID apiId = givenApi("deepgram", "Deepgram");
            ApiToolEntity t = tool(apiId, "text_to_speech", MAPPED_VOICE_SPEC);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId()))
                    .thenReturn(List.of(param("model", "[\"aura-asteria-en\",\"aura-luna-en\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("aura").orElseThrow();

            assertThat(m.catalogAllowed())
                    .containsEntry("voice", List.of("aura-asteria-en", "aura-luna-en"));
        }

        @Test
        @DisplayName("regression: a nested write path no row owns and none claims inherits nothing")
        void ignoresUnownedNestedBindingPaths() {
            // `content[0].text` addresses a place INSIDE a body. When no row is
            // named for it and no row claims it as its bodyPath, nothing here
            // knows which parameter fills it, and a neighbour's list is worse
            // than no list.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"content[0].text","style":"config.style"},
                     "models":[{"id":"acme-1","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    param("text", "[\"nope\"]"),
                    param("style", "[\"nope\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-1").orElseThrow();

            assertThat(m.catalogAllowed()).isEmpty();
        }

        @Test
        @DisplayName("a row NAMED for the nested place it fills is that place, and its list is offered")
        void aNestedNameIsTheWritePath() {
            // The corpus's ordinary encoding of a nested field: AudioCraft names
            // its parameters input.prompt and input.output_format, and the
            // descriptor writes exactly those. Refusing to match them left the
            // bug this whole matching exists to fix in place for every seed
            // written that way.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"music","assetPath":"$binary",
                     "paramMap":{"prompt":"input.prompt","style":"input.style"},
                     "models":[{"id":"acme-n","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    param("input.style", "[\"calm\",\"driving\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-n").orElseThrow();

            assertThat(m.catalogAllowed()).containsEntry("style", List.of("calm", "driving"));
        }

        @Test
        @DisplayName("a nested write path inherits from the parameter that CLAIMS it as its bodyPath")
        void inheritsThroughBodyPath() {
            // The exception to the rule above, and the only match on a nested
            // path that is not a coincidence: the row itself declares that this
            // is where its value lands, so it is the same field by construction.
            // Without it, every descriptor that writes a nested body (the shape
            // most video providers want) lost its lists.
            UUID apiId = givenApi("heygen", "HeyGen");
            String spec = """
                    {"kind":"video","assetPath":"video_url",
                     "paramMap":{"prompt":"video_inputs[0].voice.input_text",
                                 "style":"video_inputs[0].character.avatar_style"},
                     "models":[{"id":"heygen-avatar","capabilities":["prompt","style"],
                                "price":{"unit":"character","unitCredits":2}}]}
                    """;
            ApiToolEntity t = tool(apiId, "create_video", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("avatar_style", "video_inputs[0].character.avatar_style",
                            "[\"normal\",\"circle\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("heygen-avatar").orElseThrow();

            assertThat(m.catalogAllowed()).containsEntry("style", List.of("normal", "circle"));
            // The prompt's path is claimed by nobody, so it stays free text.
            assertThat(m.catalogAllowed()).doesNotContainKey("prompt");
        }

        @Test
        @DisplayName("a FLAT path is matched by name only, never by another row's bodyPath claim")
        void flatPathsAreMatchedByNameAlone() {
            // 'length_ms' claiming bodyPath 'duration' would otherwise offer
            // MILLISECONDS under a unified parameter that means seconds, which
            // is the same class of mistake the nested rule exists to prevent.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"video","assetPath":"url",
                     "paramMap":{"prompt":"prompt","duration_seconds":"duration"},
                     "models":[{"id":"acme-v","capabilities":["prompt","duration_seconds"],
                                "price":{"unit":"second","unitCredits":2},
                                "constraints":{"prompt":{"maxLength":100}}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("length_ms", "duration", "[\"1000\",\"2000\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-v").orElseThrow();

            assertThat(m.catalogAllowed()).doesNotContainKey("duration_seconds");
        }

        @Test
        @DisplayName("a parameter's own name beats another row's claim on it")
        void nameWinsOverAForeignBodyPathClaim() {
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"style"},
                     "models":[{"id":"acme-i","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("legacy_style", "style", "[\"wrong\"]"),
                    param("style", "[\"vivid\",\"natural\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-i").orElseThrow();

            assertThat(m.catalogAllowed()).containsEntry("style", List.of("vivid", "natural"));
        }

        @Test
        @DisplayName("two rows claiming one body path offer no list rather than the wrong one")
        void conflictingBodyPathClaimsOfferNothing() {
            // Nothing says which row owns the field, and a field showing another
            // parameter's values is worse than a field showing none.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"config.style"},
                     "models":[{"id":"acme-c","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("style_a", "config.style", "[\"a\"]"),
                    nestedParam("style_b", "config.style", "[\"b\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-c").orElseThrow();

            assertThat(m.catalogAllowed()).doesNotContainKey("style");
        }

        @Test
        @DisplayName("a THIRD row claiming a contested body path does not undo the refusal")
        void aThirdClaimantDoesNotUndoTheConflict() {
            // The conflict has to be remembered apart from the map: a third row
            // writing its own list over the marked entry would make two rows'
            // disagreement disappear, and the field would show whichever list
            // the database happened to return last.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"config.style"},
                     "models":[{"id":"acme-c3","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("style_a", "config.style", "[\"c\"]"),
                    nestedParam("style_b", "config.style", "[\"a\"]"),
                    nestedParam("style_c", "config.style", "[\"a\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-c3").orElseThrow();

            assertThat(m.catalogAllowed()).doesNotContainKey("style");
        }

        @Test
        @DisplayName("a SCALED binding inherits nothing even when a row claims its body path")
        void aScaledNestedBindingStillInheritsNothing() {
            // music_length_ms is milliseconds; the unified name says seconds.
            // Offering the field's own values would suggest 30000 for half a
            // minute. The bail-out predates the bodyPath index and has to
            // survive it.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"music","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt",
                                 "duration_seconds":{"path":"config.length_ms","scale":1000}},
                     "models":[{"id":"acme-m2","capabilities":["prompt","duration_seconds"],
                                "price":{"unit":"second","unitCredits":1},
                                "constraints":{"prompt":{"maxLength":100}}}]}
                    """;
            ApiToolEntity t = tool(apiId, "compose", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("length_ms", "config.length_ms", "[\"10000\",\"30000\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-m2").orElseThrow();

            assertThat(m.catalogAllowed()).doesNotContainKey("duration_seconds");
        }

        @Test
        @DisplayName("two rows claiming one path own nothing, even when their lists agree")
        void duplicateClaimsOwnNothing() {
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"config.style"},
                     "models":[{"id":"acme-same","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    nestedParam("style_a", "config.style", "[\"a\"]"),
                    nestedParam("style_b", "config.style", "[\"a\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-same").orElseThrow();

            // Agreeing today is not the same as being the owner: the seed still
            // says two rows fill one field, and which of them is authoritative
            // is a question the platform must not answer by guessing.
            assertThat(m.catalogAllowed()).doesNotContainKey("style");
        }

        @Test
        @DisplayName("a claimant with NO list of its own still contests the field")
        void aListlessClaimantStillContests() {
            // The row with no list is usually the field's real owner: an opaque
            // provider id, fetched rather than enumerated. Skipping it here let
            // the neighbour's list be offered under the owner's name.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"config.style"},
                     "models":[{"id":"acme-listless","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            ApiToolParameterEntity owner = new ApiToolParameterEntity();
            owner.setName("style_owner");
            owner.setExtras("{\"bodyPath\":\"config.style\"}");
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(
                    owner,
                    nestedParam("style_other", "config.style", "[\"wrong\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-listless").orElseThrow();

            assertThat(m.catalogAllowed()).doesNotContainKey("style");
        }

        @Test
        @DisplayName("malformed extras cost a dropdown, not the model listing")
        void malformedExtrasAreSurvivable() {
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"style"},
                     "models":[{"id":"acme-m","capabilities":["prompt","style"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            ApiToolParameterEntity broken = param("style", "[\"vivid\"]");
            broken.setExtras("not json at all");
            when(paramRepo.findByApiToolId(t.getId())).thenReturn(List.of(broken));

            GenerationRegistry.GenerationModel m = registry.resolve("acme-m").orElseThrow();

            assertThat(m.catalogAllowed()).containsEntry("style", List.of("vivid"));
        }

        @Test
        @DisplayName("a model that states its own list keeps it")
        void declaredConstraintIsNotInherited() {
            UUID apiId = givenApi("openai", "OpenAI");
            String spec = """
                    {"kind":"voice","assetPath":"$binary",
                     "paramMap":{"prompt":"text","voice":"voice"},
                     "models":[{"id":"tts","capabilities":["prompt","voice"],
                                "constraints":{"voice":{"allowed":["alloy"]}},
                                "price":{"unit":"character","unitCredits":0.2}}]}
                    """;
            ApiToolEntity t = tool(apiId, "speech", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId()))
                    .thenReturn(List.of(param("voice", "[\"alloy\",\"echo\",\"nova\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("tts").orElseThrow();

            // Nothing inherited: the descriptor narrowed the set on purpose.
            assertThat(m.catalogAllowed()).isEmpty();
        }

        @Test
        @DisplayName("a catalogue list for something the model does not accept is dropped")
        void ignoresValuesForUnofferedCapabilities() {
            // Advertising a field the model never sends would put a control on
            // screen whose value goes nowhere.
            UUID apiId = givenApi("acme", "Acme");
            String spec = """
                    {"kind":"image","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt","style":"style"},
                     "models":[{"id":"plain","capabilities":["prompt"],
                                "price":{"unit":"call","baseCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "generate", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId()))
                    .thenReturn(List.of(param("style", "[\"anime\",\"cinematic\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("plain").orElseThrow();

            assertThat(m.catalogAllowed()).isEmpty();
        }

        @Test
        @DisplayName("regression: a SCALED binding does not borrow the upstream unit's values")
        void ignoresScaledBindings() {
            // `duration_seconds` writing to `music_length_ms` multiplies by a
            // thousand, so that parameter's own values are milliseconds.
            // Offering them under a field labelled seconds would suggest 30000
            // for a half-minute clip.
            UUID apiId = givenApi("elevenlabs", "ElevenLabs");
            String spec = """
                    {"kind":"music","assetPath":"$binary",
                     "paramMap":{"prompt":"prompt",
                                 "duration_seconds":{"path":"music_length_ms","scale":1000}},
                     "models":[{"id":"music-1","capabilities":["prompt","duration_seconds"],
                                "price":{"unit":"second","unitCredits":1}}]}
                    """;
            ApiToolEntity t = tool(apiId, "compose_music", spec);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId()))
                    .thenReturn(List.of(param("music_length_ms", "[\"30000\",\"60000\"]")));

            GenerationRegistry.GenerationModel m = registry.resolve("music-1").orElseThrow();

            assertThat(m.catalogAllowed()).isEmpty();
        }

        @Test
        @DisplayName("a parameter lookup that fails costs a dropdown, never a model")
        void survivesAParameterLookupFailure() {
            UUID apiId = givenApi("deepgram", "Deepgram");
            ApiToolEntity t = tool(apiId, "text_to_speech", MAPPED_VOICE_SPEC);
            when(toolRepo.findGenerationEndpoints()).thenReturn(List.of(t));
            when(paramRepo.findByApiToolId(t.getId())).thenThrow(new RuntimeException("db down"));

            GenerationRegistry.GenerationModel m = registry.resolve("aura").orElseThrow();

            assertThat(m.catalogAllowed()).isEmpty();
        }
    }
}
