package com.apimarketplace.publication.service;

import java.util.List;

/**
 * Raised when a managed-cloud workspace tries to install a publication whose
 * snapshot uses something managed cloud cannot run at any plan (today: local-CLI
 * agents). Vector search is NOT one of these since 2026-09-03 - it is plan-gated at acquire
 * through PublicationPlanUpgradeRequiredException instead.
 *
 * <p>Distinct from {@link IllegalArgumentException} (which the controllers map
 * to 400 "bad request") because nothing about the request is malformed: the
 * publication is real and the caller is legitimate, the EDITION is the blocker.
 * Controllers map it to 403 with {@code code=CE_EXCLUSIVE} so the marketplace UI
 * can show the "self-hosted only" state instead of a generic failure.
 */
public class CeExclusivePublicationException extends RuntimeException {

    /** Detected feature codes (see {@code CeExclusiveFeatureDetector}) that triggered the refusal. */
    private final List<String> features;

    /**
     * @param message non-null user/agent-facing sentence; the controllers put it
     *                straight into a {@code Map.of(...)} body, which rejects null,
     *                so a null here would turn the refusal into a 500.
     */
    public CeExclusivePublicationException(String message, List<String> features) {
        super(message == null ? CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE : message);
        this.features = features == null ? List.of() : List.copyOf(features);
    }

    public List<String> getFeatures() {
        return features;
    }
}
