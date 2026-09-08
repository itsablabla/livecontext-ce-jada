package com.apimarketplace.catalog.service.exception;

/**
 * The caller's plan does not include this integration.
 *
 * <p>Carries the plan they would need rather than only saying "no", because
 * every surface that shows this refusal (workflow step, chat tool result, MCP
 * client) has to name the way out for it to be actionable.
 */
public class PlanUpgradeRequiredException extends RuntimeException {

    /** Machine token, so callers can recognise this refusal without parsing prose. */
    public static final String ERROR_CODE = "PLAN_UPGRADE_REQUIRED";

    private final String requiredPlan;
    private final String featureLabel;

    public PlanUpgradeRequiredException(String requiredPlan, String featureLabel) {
        super(buildMessage(requiredPlan, featureLabel));
        this.requiredPlan = requiredPlan;
        this.featureLabel = featureLabel;
    }

    public String getRequiredPlan() {
        return requiredPlan;
    }

    public String getFeatureLabel() {
        return featureLabel;
    }

    private static String buildMessage(String requiredPlan, String featureLabel) {
        String what = featureLabel != null && !featureLabel.isBlank() ? featureLabel : "This integration";
        return what + " is available from the " + requiredPlan
                + " plan. Upgrade your plan to use it, or replace this step with an integration your plan includes.";
    }
}
