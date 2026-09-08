package com.apimarketplace.publication.client;

import java.util.List;

/**
 * The publication service refused an install because the workspace's PLAN does not include a
 * capability the app uses. Today that capability is vector search.
 *
 * <p>Kept apart from {@link CeExclusiveAcquisitionException} on purpose. That one means the
 * deployment cannot run the app at all, and the agent-facing help tells an agent the refusal is
 * terminal and never to retry. This one is lifted by an upgrade, so an agent must report a
 * different thing: which plan unlocks it, and that the person, not the agent, performs the change.
 */
public class PublicationPlanUpgradeException extends RuntimeException {

    private final String requiredPlan;
    private final List<String> features;

    public PublicationPlanUpgradeException(String message, String requiredPlan, List<String> features) {
        super(message);
        this.requiredPlan = requiredPlan;
        this.features = features == null ? List.of() : List.copyOf(features);
    }

    public String getRequiredPlan() {
        return requiredPlan;
    }

    public List<String> getFeatures() {
        return features;
    }
}
