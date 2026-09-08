package com.apimarketplace.publication.service;

import java.util.List;

/**
 * Raised when a managed-cloud workspace tries to install a publication that uses a capability its
 * plan does not include. Today that capability is vector search.
 *
 * <p><b>Deliberately NOT {@link CeExclusivePublicationException}.</b> That one says "this
 * deployment cannot run the app", which the marketplace renders as a terminal dead end with no
 * retry, and which the agent help tells an agent never to retry. This one says "this workspace has
 * not bought the app's capability yet", which a person resolves by upgrading and which is
 * therefore the opposite of terminal. Reusing the CE code would have told both the user and the
 * agent to give up on something one click away.
 *
 * <p>Controllers map it to 403 with {@code code=PLAN_UPGRADE_REQUIRED} and {@code requiredPlan},
 * matching how catalog-service refuses an integration the plan does not include, so the frontend
 * and the agent already know the shape.
 */
public class PublicationPlanUpgradeRequiredException extends RuntimeException {

    /** Machine token, identical to the catalog's own refusal so one handler covers both. */
    public static final String ERROR_CODE = "PLAN_UPGRADE_REQUIRED";

    private final String requiredPlan;
    private final List<String> features;

    public PublicationPlanUpgradeRequiredException(String requiredPlan, List<String> features) {
        super(message(requiredPlan));
        this.requiredPlan = requiredPlan;
        this.features = features == null ? List.of() : List.copyOf(features);
    }

    /**
     * The plan is null when we could not read the requirement at all. Naming a tier we guessed
     * would send someone to buy the wrong one, so the sentence stays true instead of specific.
     */
    private static String message(String requiredPlan) {
        if (requiredPlan == null || requiredPlan.isBlank()) {
            return "This app uses vector search (embedding columns), which this workspace's plan "
                    + "does not include. Upgrade the workspace to install it, or choose an app "
                    + "that does not use embeddings.";
        }
        return "This app uses vector search (embedding columns), which is available from the "
                + requiredPlan + " plan. Upgrade the workspace to install it, or choose an app "
                + "that does not use embeddings.";
    }

    public String getRequiredPlan() {
        return requiredPlan;
    }

    public List<String> getFeatures() {
        return features;
    }
}
