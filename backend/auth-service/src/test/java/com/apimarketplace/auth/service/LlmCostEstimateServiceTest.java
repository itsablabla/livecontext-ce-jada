package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The picker computes its own figure from these coefficients, so they have to
 * reproduce what {@link ModelPricingService#calculateCost} will actually debit.
 * That equivalence is the whole reason the endpoint may publish two numbers
 * instead of 779 rows, and it is what this test holds.
 *
 * <p>It also holds the shape of the payload: the multiplier is folded into the
 * coefficients precisely so that no field on the wire is the platform's margin,
 * and a well-meaning refactor that "helpfully" added it back would undo that
 * without breaking anything else.
 */
class LlmCostEstimateServiceTest {

    private static final BigDecimal INPUT_RATE = new BigDecimal("2");
    private static final BigDecimal OUTPUT_RATE = new BigDecimal("10");

    private ModelPricingService pricingService(BigDecimal multiplier) {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(pricing));
        return new ModelPricingService(repository, multiplier);
    }

    private CreditService meteredCreditService(boolean unlimited) {
        CreditService creditService = mock(CreditService.class);
        when(creditService.isUnlimited()).thenReturn(unlimited);
        return creditService;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profile(Map<String, Object> basis, LlmCostProfile which) {
        Map<String, Object> profiles = (Map<String, Object>) basis.get("profiles");
        return (Map<String, Object>) profiles.get(which.key());
    }

    @ParameterizedTest
    @EnumSource(LlmCostProfile.class)
    @DisplayName("the published coefficients reproduce the cost the ledger would debit")
    void coefficientsReproduceTheBilledCost(LlmCostProfile which) {
        BigDecimal multiplier = new BigDecimal("1.11");
        ModelPricingService pricing = pricingService(multiplier);
        LlmCostEstimateService service = new LlmCostEstimateService(pricing, meteredCreditService(false));

        Map<String, Object> published = profile(service.buildBasis(), which);
        BigDecimal estimated = INPUT_RATE.multiply((BigDecimal) published.get("inputCoefficient"))
                .add(OUTPUT_RATE.multiply((BigDecimal) published.get("outputCoefficient")));

        BigDecimal billed = pricing.calculateCost("anthropic", "claude-sonnet-5", which.breakdown());
        assertThat(estimated.doubleValue()).isCloseTo(billed.doubleValue(), org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("the coefficients move with the margin lever, so a client can never hold a stale one")
    void coefficientsFollowTheMarginLever() {
        Map<String, Object> cheap = profile(
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false))
                        .buildBasis(), LlmCostProfile.AGENT_CONVERSATION);
        Map<String, Object> dear = profile(
                new LlmCostEstimateService(pricingService(new BigDecimal("1.8")), meteredCreditService(false))
                        .buildBasis(), LlmCostProfile.AGENT_CONVERSATION);

        assertThat((BigDecimal) dear.get("inputCoefficient"))
                .isGreaterThan((BigDecimal) cheap.get("inputCoefficient"));
    }

    @Test
    @DisplayName("names no margin on the wire: coefficients only, never the multiplier itself")
    void neverPublishesTheMultiplier() {
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false))
                        .buildBasis();

        assertThat(basis).doesNotContainKey("multiplier");
        assertThat(basis.toString()).doesNotContain("1.11");
        assertThat(profile(basis, LlmCostProfile.AGENT_CONVERSATION))
                .containsOnlyKeys("inputCoefficient", "outputCoefficient");
    }

    @Test
    @DisplayName("publishes every profile, so a surface can price the shape of work it configures")
    void publishesEveryProfile() {
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false))
                        .buildBasis();

        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = (Map<String, Object>) basis.get("profiles");
        assertThat(profiles).containsOnlyKeys(
                "agentConversation", "chatConversation", "guardrailCheck", "classifyStep");
    }

    @Test
    @DisplayName("an install that does not meter credits gets no coefficients at all")
    void disabledWhereCreditsAreNotMetered() {
        // CE bills at provider cost with no margin and has no wallet. A credit
        // figure there would be a number about nothing, so nothing is published.
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.0")), meteredCreditService(true))
                        .buildBasis();

        assertThat(basis).containsEntry("enabled", false);
        assertThat((Map<?, ?>) basis.get("profiles")).isEmpty();
    }
}
