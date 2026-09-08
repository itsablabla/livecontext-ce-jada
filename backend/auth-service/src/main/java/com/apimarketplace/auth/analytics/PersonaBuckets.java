package com.apimarketplace.auth.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded persona vocabularies for product analytics.
 *
 * <p>The onboarding form lets a user replace the {@code other} sentinel with free
 * text before posting (a custom role, interest or use case), and that text is
 * persisted as-is. Analytics must never carry it: it is user content and can be
 * identifying. Every value is therefore re-bucketed here against the exact option
 * lists the onboarding page offers (cloud AND self-hosted variants); anything
 * else collapses to {@code other}. Keep these sets in sync with the frontend
 * onboarding page when an option is added.</p>
 */
public final class PersonaBuckets {

    public static final String OTHER = "other";

    static final Set<String> PROFESSIONS = Set.of(
            // cloud
            "sales", "marketing", "customer-success", "support", "ecommerce", "operations",
            "product", "engineering", "data-analytics", "finance", "hr", "founder", "freelancer",
            // self-hosted
            "instance-admin", "maintainer", "workflow-builder", "developer", "community",
            OTHER);

    static final Set<String> COMPANY_SIZES = Set.of(
            "solo", "startup", "small", "medium", "enterprise",
            "team", "department", "community");

    static final Set<String> INTERESTS = Set.of(
            "automation", "ai-ml", "data-analytics", "integrations", "productivity",
            "business-intelligence", "customer-experience", "sales-crm", "marketing-automation",
            "ecommerce-tools",
            "self-hosted-agents", "local-automation", "private-data", "team-workflows",
            "local-marketplace", "governance",
            OTHER);

    static final Set<String> USE_CASES = Set.of(
            "workflow-automation", "chatbots-assistants", "data-integration", "content-generation",
            "lead-generation", "customer-support", "reporting-dashboards", "ecommerce-automation",
            "team-collaboration", "monitoring-alerts",
            "internal-automation", "private-assistants", "data-pipelines", "tool-orchestration",
            "team-workspaces", "marketplace-publishing", "evaluation-sandbox",
            OTHER);

    static final Set<String> EXPERIENCE_LEVELS = Set.of(
            "beginner", "intermediate", "advanced",
            "first-install", "operator", "power-user");

    /** What to automate first. Cloud goals, then the self-hosted ones (= CE use cases). */
    static final Set<String> PRIMARY_GOALS = Set.of(
            "email-follow-ups", "content-publishing", "lead-generation", "customer-support",
            "reporting", "data-sync", "monitoring-alerts", "ai-assistant",
            "internal-automation", "private-assistants", "data-pipelines", "tool-orchestration",
            "team-workspaces", "marketplace-publishing", "evaluation-sandbox",
            OTHER);

    static final Set<String> TOOLS = Set.of(
            "gmail", "outlook", "google-sheets", "slack", "notion", "hubspot", "salesforce",
            "shopify", "stripe", "github", "discord", "telegram", "linkedin", "airtable",
            OTHER);

    static final Set<String> PREVIOUS_TOOLS = Set.of(
            "none", "zapier-make", "n8n", "custom-code", "other-platform");

    static final Set<String> REFERRAL_SOURCES = Set.of(
            "search", "social", "word-of-mouth", "github", "article", "ai-assistant", OTHER);

    private PersonaBuckets() {}

    public static String profession(String raw) {
        return bucket(raw, PROFESSIONS);
    }

    public static String companySize(String raw) {
        return bucket(raw, COMPANY_SIZES);
    }

    public static String experienceLevel(String raw) {
        return bucket(raw, EXPERIENCE_LEVELS);
    }

    public static List<String> interests(List<String> raw) {
        return bucketAll(raw, INTERESTS);
    }

    public static List<String> useCases(List<String> raw) {
        return bucketAll(raw, USE_CASES);
    }

    public static String primaryGoal(String raw) {
        return bucket(raw, PRIMARY_GOALS);
    }

    public static List<String> toolsUsed(List<String> raw) {
        return bucketAll(raw, TOOLS);
    }

    public static String previousTool(String raw) {
        return bucket(raw, PREVIOUS_TOOLS);
    }

    public static String referralSource(String raw) {
        return bucket(raw, REFERRAL_SOURCES);
    }

    /** Number of entries that were NOT a known option (i.e. free text the user typed). */
    public static int customCount(List<String> raw, Set<String> allowed) {
        if (raw == null) return 0;
        int n = 0;
        for (String v : raw) {
            String norm = normalize(v);
            if (norm != null && !allowed.contains(norm)) n++;
        }
        return n;
    }

    /** Null stays null (not answered); a known option passes; anything else is {@code other}. */
    static String bucket(String raw, Set<String> allowed) {
        String norm = normalize(raw);
        if (norm == null) return null;
        return allowed.contains(norm) ? norm : OTHER;
    }

    /** Known options pass through (deduplicated, order kept); unknown ones collapse to one {@code other}. */
    static List<String> bucketAll(List<String> raw, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        boolean sawOther = false;
        for (String v : raw) {
            String b = bucket(v, allowed);
            if (b == null) continue;
            if (OTHER.equals(b)) {
                sawOther = true;
                continue;
            }
            if (!out.contains(b)) out.add(b);
        }
        if (sawOther) out.add(OTHER);
        return out;
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
