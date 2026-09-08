package com.apimarketplace.publication.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Decoding of publication-service's CE-exclusive refusal.
 *
 * <p>All three internal acquire endpoints answer 403 with
 * {@code {"error":…,"code":"CE_EXCLUSIVE","features":[…]}}. The client must turn
 * that into a TYPED exception so the agent-facing tool can report the
 * constraint (terminal, no retry) instead of a generic "acquire failed" that an
 * agent would retry. A plain 403 (a real authorization failure) must keep its
 * existing generic handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationClient - CE-exclusive 403 decoding")
class PublicationClientCeExclusiveTest {

    private static final String BASE_URL = "http://localhost:8092";
    private static final UUID PUB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CE_BODY =
            "{\"error\":\"This app is Community Edition exclusive.\","
                    + "\"code\":\"CE_EXCLUSIVE\",\"features\":[\"CLI_AGENT\"]}";
    /** Same status, same shape, different code: the refusal an upgrade lifts. */
    private static final String PLAN_BODY =
            "{\"error\":\"This app uses vector search, available from the PRO plan.\","
                    + "\"code\":\"PLAN_UPGRADE_REQUIRED\",\"requiredPlan\":\"PRO\","
                    + "\"features\":[\"VECTOR_SEARCH\"]}";

    @Mock private RestTemplate restTemplate;

    private PublicationClient client;

    @BeforeEach
    void setUp() {
        client = new PublicationClient(restTemplate, BASE_URL);
    }

    @SuppressWarnings("unchecked")
    private void stubForbidden(String path, String body) {
        when(restTemplate.exchange(eq(BASE_URL + "/api/internal/publications/" + PUB_ID + path),
                eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("workflow acquire → typed exception carrying the message and the features")
    void workflowAcquireDecodesCeExclusive() {
        stubForbidden("/acquire", CE_BODY);

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .isInstanceOf(CeExclusiveAcquisitionException.class)
                .hasMessage("This app is Community Edition exclusive.")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(CeExclusiveAcquisitionException.class))
                .extracting(CeExclusiveAcquisitionException::getFeatures)
                .isEqualTo(List.of("CLI_AGENT"));
    }

    @Test
    @DisplayName("a plan refusal decodes to its OWN exception, not the terminal CE one")
    void planRefusalIsItsOwnException() {
        // Mapping this onto CeExclusiveAcquisitionException would be worse than an untyped
        // failure: the agent help documents that exception as terminal, so the agent would tell
        // the user to give up on an install one upgrade away.
        stubForbidden("/acquire", PLAN_BODY);

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .isInstanceOf(PublicationPlanUpgradeException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(PublicationPlanUpgradeException.class))
                .extracting(PublicationPlanUpgradeException::getRequiredPlan)
                .isEqualTo("PRO");
    }

    @Test
    @DisplayName("a plan refusal carries the features, so the caller can say WHAT needs the plan")
    void planRefusalCarriesFeatures() {
        stubForbidden("/acquire", PLAN_BODY);

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(PublicationPlanUpgradeException.class))
                .extracting(PublicationPlanUpgradeException::getFeatures)
                .isEqualTo(List.of("VECTOR_SEARCH"));
    }

    @Test
    @DisplayName("agent acquire decodes it too (not only the workflow path)")
    void agentAcquireDecodesCeExclusive() {
        stubForbidden("/acquire-agent", CE_BODY);

        assertThatThrownBy(() -> client.acquireAgentPublication(PUB_ID, "7", "org-1"))
                .isInstanceOf(CeExclusiveAcquisitionException.class);
    }

    @Test
    @DisplayName("resource acquire decodes it too")
    void resourceAcquireDecodesCeExclusive() {
        stubForbidden("/acquire-resource", CE_BODY);

        assertThatThrownBy(() -> client.acquireResource(PUB_ID, "7", "org-1"))
                .isInstanceOf(CeExclusiveAcquisitionException.class);
    }

    @Test
    @DisplayName("a plain 403 (real authorization failure) keeps the generic handling")
    void unrelatedForbiddenStaysGeneric() {
        stubForbidden("/acquire", "{\"error\":\"forbidden\"}");

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(CeExclusiveAcquisitionException.class);
    }

    @Test
    @DisplayName("a malformed 403 body does not blow up the client")
    void malformedBodyStaysGeneric() {
        stubForbidden("/acquire", "not json at all");

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(CeExclusiveAcquisitionException.class);
    }

    @Test
    @DisplayName("a CE_EXCLUSIVE body with no features yields an empty feature list, not null")
    void missingFeaturesYieldsEmptyList() {
        stubForbidden("/acquire", "{\"error\":\"nope\",\"code\":\"CE_EXCLUSIVE\"}");

        assertThatThrownBy(() -> client.acquirePublication(PUB_ID, "7", "org-1"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(CeExclusiveAcquisitionException.class))
                .extracting(CeExclusiveAcquisitionException::getFeatures)
                .isEqualTo(List.of());
    }
}
