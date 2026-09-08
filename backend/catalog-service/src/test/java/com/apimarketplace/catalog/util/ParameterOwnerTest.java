package com.apimarketplace.catalog.util;

import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which row owns the place a descriptor writes to.
 *
 * <p>Every case here decides two things at once, and that is the point of the
 * class: the values a generation surface offers for a parameter, and the source
 * it fetches them from. Answering them off different rows is how a field came to
 * be described with one parameter's enumeration and another's provider source,
 * each half looking right on its own.
 *
 * <p>The row order in these fixtures is deliberate. The repository query has no
 * {@code ORDER BY}, so anything that depends on it answers differently on two
 * different days, and every ambiguous case here is asserted in both orders.
 */
class ParameterOwnerTest {

    private static ApiToolParameterEntity row(String name, String extras) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setName(name);
        p.setExtras(extras);
        return p;
    }

    private static String claims(String bodyPath) {
        return "{\"bodyPath\":\"" + bodyPath + "\"}";
    }

    private static Optional<String> ownerName(List<ApiToolParameterEntity> rows, String path) {
        return ParameterOwner.of(rows, path).map(ApiToolParameterEntity::getName);
    }

    @Test
    @DisplayName("a row named for the path owns it")
    void nameOwns() {
        List<ApiToolParameterEntity> rows = List.of(row("voice_id", null), row("text", null));

        assertThat(ownerName(rows, "voice_id")).contains("voice_id");
    }

    @Test
    @DisplayName("a NESTED name owns its path too: plenty of rows are named for the place they fill")
    void nestedNameOwns() {
        // AudioCraft names its parameters input.prompt and input.duration, and
        // the descriptor writes exactly those.
        List<ApiToolParameterEntity> rows = List.of(row("input.prompt", null));

        assertThat(ownerName(rows, "input.prompt")).contains("input.prompt");
    }

    @Test
    @DisplayName("a row claiming a nested path as its bodyPath owns it when nothing is named for it")
    void claimOwnsWhenNothingIsNamed() {
        List<ApiToolParameterEntity> rows = List.of(
                row("voice_id", claims("video_inputs[0].voice.voice_id")), row("title", null));

        assertThat(ownerName(rows, "video_inputs[0].voice.voice_id")).contains("voice_id");
    }

    @Test
    @DisplayName("a NAME beats a foreign claim on the same path, in either row order")
    void nameBeatsClaim() {
        ApiToolParameterEntity named = row("config.style", null);
        ApiToolParameterEntity claimant = row("legacy", claims("config.style"));

        assertThat(ownerName(List.of(named, claimant), "config.style")).contains("config.style");
        assertThat(ownerName(List.of(claimant, named), "config.style")).contains("config.style");
    }

    @Test
    @DisplayName("a FLAT path is a name and nothing else: a foreign flat claim owns nothing")
    void flatClaimsOwnNothing() {
        // A row named length_ms claiming bodyPath 'duration' would otherwise
        // answer for a parameter that means seconds with values in milliseconds.
        List<ApiToolParameterEntity> rows = List.of(row("length_ms", claims("duration")));

        assertThat(ParameterOwner.of(rows, "duration")).isEmpty();
    }

    @Test
    @DisplayName("two rows of the same name own nothing, in either order")
    void duplicateNamesOwnNothing() {
        ApiToolParameterEntity a = row("style", "{\"a\":1}");
        ApiToolParameterEntity b = row("style", "{\"b\":2}");

        assertThat(ParameterOwner.of(List.of(a, b), "style")).isEmpty();
        assertThat(ParameterOwner.of(List.of(b, a), "style")).isEmpty();
    }

    @Test
    @DisplayName("two claimants own nothing, even when they claim it identically")
    void duplicateClaimsOwnNothing() {
        List<ApiToolParameterEntity> rows = List.of(
                row("a", claims("config.style")), row("b", claims("config.style")));

        assertThat(ParameterOwner.of(rows, "config.style")).isEmpty();
    }

    @Test
    @DisplayName("a duplicated name is not rescued by a claim: ambiguity is ambiguity")
    void duplicateNamesAreNotRescuedByAClaim() {
        List<ApiToolParameterEntity> rows = List.of(
                row("config.style", null), row("config.style", null), row("c", claims("config.style")));

        assertThat(ParameterOwner.of(rows, "config.style")).isEmpty();
    }

    @Test
    @DisplayName("nothing owns nothing: no rows, no path, a path nobody fills")
    void absences() {
        assertThat(ParameterOwner.of(null, "voice_id")).isEmpty();
        assertThat(ParameterOwner.of(List.of(), "voice_id")).isEmpty();
        assertThat(ParameterOwner.of(List.of(row("a", null)), null)).isEmpty();
        assertThat(ParameterOwner.of(List.of(row("a", null)), "  ")).isEmpty();
        assertThat(ParameterOwner.of(List.of(row("a", null)), "b.c")).isEmpty();
    }

    @Test
    @DisplayName("a row that declares it writes elsewhere does not own its own name")
    void aRowThatWritesElsewhereDoesNotOwnItsName() {
        // Its value lands at other.place, so offering its values under its name
        // would describe a field the request never fills.
        List<ApiToolParameterEntity> rows = List.of(row("input.prompt", claims("other.place")));

        assertThat(ParameterOwner.of(rows, "input.prompt")).isEmpty();
        assertThat(ownerName(rows, "other.place")).contains("input.prompt");
    }

    @Test
    @DisplayName("a row with no name at all owns nothing by name, and can still claim")
    void aNamelessRowOwnsNothingByName() {
        ApiToolParameterEntity nameless = row(null, claims("config.style"));

        assertThat(ownerName(List.of(nameless), "config.style")).isEmpty();
        assertThat(ParameterOwner.of(List.of(nameless), "config.style")).isPresent();
    }

    @Test
    @DisplayName("a malformed extras blob costs a claim, not an exception")
    void malformedExtrasAreSurvivable() {
        List<ApiToolParameterEntity> rows = List.of(row("a", "not json at all"));

        assertThat(ParameterOwner.of(rows, "config.style")).isEmpty();
    }

    @Test
    @DisplayName("a nested path is recognised by a dot or by an index")
    void nestedShapes() {
        assertThat(ParameterOwner.isNested("config.style")).isTrue();
        assertThat(ParameterOwner.isNested("image[0]")).isTrue();
        assertThat(ParameterOwner.isNested("voice_id")).isFalse();
        assertThat(ParameterOwner.isNested(null)).isFalse();
    }
}
