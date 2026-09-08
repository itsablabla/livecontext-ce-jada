package com.apimarketplace.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the body path a parameter claims.
 *
 * <p>Every case here is a row that can reach this method from the catalogue,
 * and the answer decides whether a generation surface finds a parameter's
 * values or shows an empty field. It runs while a model list is being built, so
 * the one thing it must never do is throw: a malformed row costs a dropdown,
 * not a listing.
 */
class ParameterBodyPathTest {

    @Test
    @DisplayName("the declared path is returned")
    void readsTheDeclaredPath() {
        assertThat(ParameterBodyPath.of("{\"bodyPath\":\"video_inputs[0].voice.voice_id\"}"))
                .isEqualTo("video_inputs[0].voice.voice_id");
    }

    @Test
    @DisplayName("a path is trimmed, because the descriptor path it is compared against is")
    void trimsThePath() {
        // Untrimmed, ' voice_id ' matches no descriptor and the parameter goes
        // back to looking like free text, with nothing said anywhere.
        assertThat(ParameterBodyPath.of("{\"bodyPath\":\"  voice_id  \"}")).isEqualTo("voice_id");
    }

    @Test
    @DisplayName("no extras at all is no claim")
    void nullAndBlankExtrasClaimNothing() {
        assertThat(ParameterBodyPath.of(null)).isNull();
        assertThat(ParameterBodyPath.of("")).isNull();
        assertThat(ParameterBodyPath.of("   ")).isNull();
    }

    @Test
    @DisplayName("extras without a bodyPath, or with an explicit null, is no claim")
    void missingOrNullKeyClaimsNothing() {
        assertThat(ParameterBodyPath.of("{\"encoding\":\"json\"}")).isNull();
        assertThat(ParameterBodyPath.of("{\"bodyPath\":null}")).isNull();
    }

    @Test
    @DisplayName("a blank claim is no claim: an empty path would match a descriptor that writes nowhere")
    void blankClaimIsNoClaim() {
        assertThat(ParameterBodyPath.of("{\"bodyPath\":\"\"}")).isNull();
        assertThat(ParameterBodyPath.of("{\"bodyPath\":\"   \"}")).isNull();
    }

    @Test
    @DisplayName("a non-textual claim is read as text, and an object or array claims nothing")
    void nonTextualClaims() {
        // A number is a usable, if odd, field name; a container has no text form
        // worth matching, and Jackson renders it as the empty string.
        assertThat(ParameterBodyPath.of("{\"bodyPath\":5}")).isEqualTo("5");
        assertThat(ParameterBodyPath.of("{\"bodyPath\":{}}")).isNull();
        assertThat(ParameterBodyPath.of("{\"bodyPath\":[]}")).isNull();
    }

    @Test
    @DisplayName("unreadable extras leave the parameter plain instead of breaking a model listing")
    void malformedExtrasDoNotThrow() {
        assertThat(ParameterBodyPath.of("not json at all")).isNull();
        assertThat(ParameterBodyPath.of("{\"bodyPath\":")).isNull();
    }
}
