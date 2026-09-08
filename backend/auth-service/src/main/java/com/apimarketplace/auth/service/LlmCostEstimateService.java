package com.apimarketplace.auth.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes what a client needs to show a pre-flight credit estimate next to a
 * model, and nothing else.
 *
 * <h2>Two coefficients, not a multiplier and a token count</h2>
 * The obvious payload would be the billing multiplier plus each profile's token
 * workload, and the client would multiply them out. It would also put a field
 * called "multiplier" holding the platform's gross margin into a response any
 * reader can open in a browser's network tab. So the two are folded together
 * before they leave: each profile ships the coefficient its rate is multiplied
 * by, and the margin is not a labelled value anywhere on the wire.
 *
 * <p>This is presentation, not secrecy. The number the user is shown IS the
 * credits they will be charged, and the ledger states the tokens and the model
 * for every debit. What the payload avoids is handing over a named margin.
 *
 * <h2>The contract</h2>
 * {@code credits = inputRate x inputCoefficient + outputRate x outputCoefficient},
 * with rates in USD per 1M tokens. That is exactly
 * {@link ModelPricingService#calculateCost} for a cache-free workload, which is
 * why {@link LlmCostProfile} carries no cache tokens and why
 * {@code LlmCostProfileFormulaTest} pins the equivalence: the estimate a picker
 * renders is the figure the ledger will debit, not an approximation of it.
 */
@Service
public class LlmCostEstimateService {

    /** Rates are USD per 1M tokens; credits are USD x 1000. */
    private static final BigDecimal RATE_SCALE = new BigDecimal("1000");

    /** Enough precision that the cheapest profile on the cheapest model still moves. */
    private static final int COEFFICIENT_SCALE = 8;

    private final ModelPricingService pricingService;
    private final CreditService creditService;

    public LlmCostEstimateService(ModelPricingService pricingService, CreditService creditService) {
        this.pricingService = pricingService;
        this.creditService = creditService;
    }

    /**
     * The estimate basis, or a disabled answer where credits are not metered.
     *
     * <p>An install that does not meter (CE) has no margin, no wallet and nothing
     * to estimate, so it gets {@code enabled=false} and no coefficients at all
     * rather than a number that would mean nothing there.
     */
    public Map<String, Object> buildBasis() {
        if (creditService.isUnlimited()) {
            return Map.of("enabled", false, "profiles", Map.of());
        }

        BigDecimal multiplier = pricingService.getCloudLlmBillingMultiplier();
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (LlmCostProfile profile : LlmCostProfile.values()) {
            profiles.put(profile.key(), Map.of(
                    "inputCoefficient", coefficient(profile.inputTokens(), multiplier),
                    "outputCoefficient", coefficient(profile.outputTokens(), multiplier)));
        }
        return Map.of("enabled", true, "profiles", profiles);
    }

    private BigDecimal coefficient(int tokens, BigDecimal multiplier) {
        return BigDecimal.valueOf(tokens)
                .multiply(multiplier)
                .divide(RATE_SCALE, COEFFICIENT_SCALE, RoundingMode.HALF_UP);
    }
}
