package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model picker shows what a model will cost BEFORE it is called, and it
 * computes that number itself from the model's list rates plus the two values
 * {@code GET /api/credits/estimate-basis} publishes. That arithmetic is only
 * honest if it lands on the same figure {@link ModelPricingService#calculateCost}
 * will later debit.
 *
 * <p>This test is the contract between the two. It pins that a
 * {@link LlmCostProfile} workload costs exactly
 * {@code (inputRate x in + outputRate x out) / 1000 x multiplier} - for EVERY
 * provider family, which is the whole reason the profiles carry no cache tokens.
 * Add a cache field to a profile and the families diverge (Anthropic weights
 * reads at 0.1x, OpenAI cached at 0.5x, Gemini at 0.25x), the shared formula
 * stops holding, and this test says so instead of the picker quietly quoting a
 * price nobody is charged.
 */
class LlmCostProfileFormulaTest {

    private static final BigDecimal INPUT_RATE = new BigDecimal("2.000000");
    private static final BigDecimal OUTPUT_RATE = new BigDecimal("10.000000");
    private static final BigDecimal MULTIPLIER = new BigDecimal("1.11");

    private ModelPricingRepository repository;
    private ModelPricingService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(pricing));
        service = new ModelPricingService(repository, MULTIPLIER);
    }

    /** The arithmetic the client is allowed to do, written out once. */
    private static BigDecimal sharedFormula(LlmCostProfile profile) {
        return INPUT_RATE.multiply(BigDecimal.valueOf(profile.inputTokens()))
                .add(OUTPUT_RATE.multiply(BigDecimal.valueOf(profile.outputTokens())))
                .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP)
                .multiply(MULTIPLIER);
    }

    @ParameterizedTest
    @EnumSource(LlmCostProfile.class)
    @DisplayName("every profile costs exactly (inputRate x in + outputRate x out) / 1000 x multiplier")
    void profileMatchesSharedFormula(LlmCostProfile profile) {
        BigDecimal billed = service.calculateCost("anthropic", "claude-sonnet-5", profile.breakdown());

        assertThat(billed).isEqualByComparingTo(sharedFormula(profile));
    }

    @ParameterizedTest
    @CsvSource({
            "anthropic", "claude-code", "openai", "codex", "azure-openai",
            "xai", "openrouter", "zai", "perplexity", "cohere",
            "deepseek", "google", "gemini", "gemini-cli", "some-unknown-vendor",
    })
    @DisplayName("the estimate does not depend on the provider family, so two models stay comparable")
    void everyFamilyPricesTheSameWorkloadIdentically(String provider) {
        // A cache-carrying breakdown would NOT satisfy this: each family weights
        // its cache fields differently. That is exactly why the profiles are
        // cache-free, and why a picker can put two providers side by side.
        BigDecimal billed = service.calculateCost(provider, "any-model",
                LlmCostProfile.AGENT_CONVERSATION.breakdown());

        assertThat(billed).isEqualByComparingTo(sharedFormula(LlmCostProfile.AGENT_CONVERSATION));
    }

    @Test
    @DisplayName("the profiles stay ordered: agent conversation dearest, classify step cheapest")
    void profilesKeepTheirOrderOfMagnitude() {
        BigDecimal agent = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.AGENT_CONVERSATION.breakdown());
        BigDecimal chat = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdown());
        BigDecimal guardrail = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.GUARDRAIL_CHECK.breakdown());
        BigDecimal classify = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CLASSIFY_STEP.breakdown());

        assertThat(agent).isGreaterThan(chat);
        assertThat(chat).isGreaterThan(guardrail);
        assertThat(guardrail).isGreaterThan(classify);
    }

    @Test
    @DisplayName("the profiles still reproduce the measured medians the pricing page publishes")
    void profilesReproduceTheMeasuredMedians() {
        // The public "5,000 credits buys N conversations" figures come from the same
        // measurement these profiles encode. Drift here silently makes the pricing
        // page and the model picker disagree about the same model.
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.AGENT_CONVERSATION.breakdown()).doubleValue())
                .isCloseTo(285d, org.assertj.core.data.Offset.offset(10d));
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdown()).doubleValue())
                .isCloseTo(80d, org.assertj.core.data.Offset.offset(5d));
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.GUARDRAIL_CHECK.breakdown()).doubleValue())
                .isCloseTo(37d, org.assertj.core.data.Offset.offset(5d));
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CLASSIFY_STEP.breakdown()).doubleValue())
                .isCloseTo(3d, org.assertj.core.data.Offset.offset(1d));
    }
}
