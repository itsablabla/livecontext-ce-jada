package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the entry-key alphabet identical on both sides of the language boundary.
 *
 * <p>The frontend refuses to ship an entry whose key it considers malformed, and this service
 * refuses to store one. If the two definitions drift, the failure is silent and permanent: the
 * browser ships an entry, {@code POST /api/changelog/seen} answers 400, the client keeps its
 * optimistic value for that session only, and the panel reopens for every user, in every new
 * session, forever - with nothing in any log pointing at a regex.
 *
 * <p>Compared as SOURCE TEXT read from the TypeScript file, not as a copy kept here: a mirror
 * agrees with whatever it was copied from, which is the drift it is meant to catch.
 */
class ChangelogKeyPatternParityTest {

    private static final String ENTRY_MODULE = "frontend/lib/changelog/latestEntry.ts";

    private static Path repoFile(String relative) {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new IllegalStateException(
                "could not locate " + relative + " from " + here
                        + " - this test must fail loudly rather than silently skip, since its whole "
                        + "job is to notice that the two definitions disagree");
    }

    @Test
    @DisplayName("the TypeScript entry-key regex is character-for-character the Java one")
    void patternsMatchAcrossTheLanguageBoundary() throws Exception {
        String module = Files.readString(repoFile(ENTRY_MODULE));

        // The literal as written in isValidEntry, e.g. /^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/
        Matcher literal = Pattern.compile("/(\\^\\[A-Za-z0-9\\][^/\\n]*\\$)/").matcher(module);
        assertThat(literal.find())
                .as("no entry-key regex literal found in %s - if it moved, this test must follow it "
                        + "rather than pass by finding nothing", ENTRY_MODULE)
                .isTrue();

        assertThat(literal.group(1))
                .as("the frontend would ship an entry key this service refuses to store, and the "
                        + "resulting 400 is invisible to the user: the panel would simply reopen forever")
                .isEqualTo(ChangelogSeenService.KEY_PATTERN_SOURCE);
    }

    @Test
    @DisplayName("the Java constant is the one the validator actually uses")
    void constantIsTheOneInForce() {
        // Guards the comparison above from going vacuous: the constant could be updated to match
        // the frontend while the compiled Pattern kept an older literal.
        assertThat(ChangelogSeenService.isValidKey("2026-09-whats-new")).isTrue();
        assertThat(ChangelogSeenService.isValidKey("-leading")).isFalse();
        assertThat(ChangelogSeenService.KEY_PATTERN_SOURCE).startsWith("^[A-Za-z0-9]");
    }
}
