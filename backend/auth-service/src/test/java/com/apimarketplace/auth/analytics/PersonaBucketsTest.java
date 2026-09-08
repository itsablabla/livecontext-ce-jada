package com.apimarketplace.auth.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Persona values leave the server ONLY as options the onboarding page offers.
 * The user can replace the {@code other} sentinel with free text; that text is
 * user content and must collapse to {@code other} here.
 */
class PersonaBucketsTest {

    @Test
    @DisplayName("a known option passes through, normalised")
    void knownOption() {
        assertEquals("engineering", PersonaBuckets.profession(" Engineering "));
        assertEquals("startup", PersonaBuckets.companySize("startup"));
        assertEquals("power-user", PersonaBuckets.experienceLevel("POWER-USER"));
    }

    @Test
    @DisplayName("free text typed in place of 'other' collapses to 'other' (never emitted)")
    void freeTextCollapses() {
        assertEquals("other", PersonaBuckets.profession("Head of Growth at Acme, jane@acme.com"));
        assertEquals("other", PersonaBuckets.companySize("about 37 people"));
    }

    @Test
    @DisplayName("unanswered stays null (not 'other'): absence is a signal of its own")
    void nullStaysNull() {
        assertNull(PersonaBuckets.profession(null));
        assertNull(PersonaBuckets.profession("  "));
        assertNull(PersonaBuckets.experienceLevel(null));
    }

    @Test
    @DisplayName("lists keep known options in order, deduplicate, and fold every custom entry into one 'other'")
    void lists() {
        List<String> raw = Arrays.asList("automation", "My secret project", "ai-ml", "automation", "another custom one", null);
        assertEquals(List.of("automation", "ai-ml", "other"), PersonaBuckets.interests(raw));
        assertEquals(2, PersonaBuckets.customCount(raw, PersonaBuckets.INTERESTS));
        assertEquals(List.of(), PersonaBuckets.useCases(null));
        assertEquals(0, PersonaBuckets.customCount(null, PersonaBuckets.USE_CASES));
    }

    @Test
    @DisplayName("the self-hosted vocabularies are accepted alongside the cloud ones")
    void ceVocabulary() {
        assertEquals("instance-admin", PersonaBuckets.profession("instance-admin"));
        assertEquals(List.of("self-hosted-agents", "governance"), PersonaBuckets.interests(List.of("self-hosted-agents", "governance")));
        assertEquals(List.of("data-pipelines"), PersonaBuckets.useCases(List.of("data-pipelines")));
        assertEquals("first-install", PersonaBuckets.experienceLevel("first-install"));
    }

    @Test
    @DisplayName("the persona questions (goal, tools, previous tool, referral) are bounded the same way")
    void personaQuestions() {
        assertEquals("email-follow-ups", PersonaBuckets.primaryGoal("Email-Follow-Ups"));
        assertEquals("private-assistants", PersonaBuckets.primaryGoal("private-assistants"));
        assertEquals("other", PersonaBuckets.primaryGoal("automate my whole company"));
        assertNull(PersonaBuckets.primaryGoal(null));
        assertEquals(List.of("gmail", "slack", "other"), PersonaBuckets.toolsUsed(List.of("gmail", "Slack", "MyInternalTool", "gmail")));
        assertEquals("zapier-make", PersonaBuckets.previousTool("zapier-make"));
        assertEquals("other", PersonaBuckets.previousTool("zapier"));
        assertEquals("word-of-mouth", PersonaBuckets.referralSource("word-of-mouth"));
        assertEquals("other", PersonaBuckets.referralSource("my cousin"));
    }
}
