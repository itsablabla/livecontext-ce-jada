package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.domain.UsageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.apimarketplace.common.web.UrlSafetyValidator;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Generic OpenAI-compatible LLM provider.
 * Works with any provider that exposes the OpenAI chat completions API format
 * (xAI/Grok, Perplexity, Cohere, Z.AI/GLM, OpenRouter, etc.).
 *
 * <p>Instances are created by {@link OpenAICompatibleProviderFactory} from YAML config,
 * not via Spring {@code @Component} - the factory registers them with the
 * {@link LLMProviderFactory}.
 */
@Slf4j
public class OpenAICompatibleProvider extends AbstractLLMProvider {

    /** Discovery is a small JSON GET, not a completion - keep it short. */
    static final int DISCOVERY_CONNECT_TIMEOUT_MS = 5_000;
    static final int DISCOVERY_READ_TIMEOUT_MS = 10_000;

    private final String providerName;
    private final String apiUrl;
    private final String apiKey;
    private final List<String> models;
    private final int displayOrder;

    /** Lazily built, mirrors the webClient pattern in the parent class. */
    private volatile RestTemplate discoveryRestTemplate;
    private final Object discoveryRestTemplateLock = new Object();

    public OpenAICompatibleProvider(String providerName, String apiUrl, String apiKey,
                                     List<String> models, int displayOrder) {
        this.providerName = providerName;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.models = models != null ? List.copyOf(models) : List.of();
        this.displayOrder = displayOrder;
    }

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getDefaultModel() {
        return models.isEmpty() ? null : models.get(0);
    }

    @Override
    public List<String> getSupportedModels() {
        return models;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    // isConfigured() is intentionally NOT overridden - the parent
    // AbstractLLMProvider.isConfigured() calls resolveApiKey() which
    // checks DB-stored keys via credentialResolver, then falls back
    // to the env-injected apiKey field. Overriding here would bypass
    // DB credential resolution entirely.

    /**
     * Ask the provider itself which models it serves, via the OpenAI-compatible
     * {@code GET <base>/models} endpoint.
     *
     * <p>This is the ONLY authoritative answer to "what does this vendor offer
     * today". The catalog feeds are third-party mirrors and lag badly for some
     * vendors: measured 2026-08-19, LiteLLM's {@code zai} block stopped at
     * glm-5.1 while Z.AI had already shipped glm-5.2, glm-5.3 and
     * glm-5v-turbo, and its {@code moonshot} block stopped at kimi-k2.6 while
     * Kimi K3 had been out for a month. A vendor's own endpoint has neither
     * lag nor a mirror's editorial choices.
     *
     * <p>What it does NOT give is pricing - {@code /models} carries none. So a
     * caller must treat the result as an EXISTENCE list only and source the
     * price elsewhere. Never infer a price from an aggregator's row for the
     * same id: an aggregator quotes its own resale rate, not the vendor's
     * direct rate (measured: z-ai/glm-5.1 at 0.966/3.036 on OpenRouter vs
     * 1.40/4.40 on Z.AI direct).
     *
     * <p>Fails soft: an unconfigured provider, a network error, an auth
     * rejection or an unexpected body all yield {@link Optional#empty()}, never
     * an exception. A discovery pass must not be able to break a catalog sync.
     * Empty is "could not ask", which is deliberately distinct from a present
     * but empty list ("asked, vendor serves nothing").
     */
    public Optional<List<String>> listRemoteModelIds() {
        if (!isConfigured()) {
            return Optional.empty();
        }
        String url = null;
        try {
            url = modelsEndpoint();
            if (url == null) {
                return Optional.empty();
            }
            UrlSafetyValidator.validateUrl(url);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(resolveApiKey());
            ResponseEntity<Map> response = discoveryRestTemplate().exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            return Optional.of(extractModelIds(response.getBody()));
        } catch (Exception e) {
            log.warn("Model discovery failed for provider '{}' at {}: {}",
                    providerName, url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A separate, tightly-bounded {@link RestTemplate} for the {@code /models}
     * call.
     *
     * <p>It must NOT reuse the completions {@code restTemplate}: that one is
     * sized for LLM generation and carries a 1-hour read timeout
     * ({@code ai.agent.llm.read-timeout-ms}), which is right for a long
     * completion and catastrophic here. Discovery runs inside the catalog
     * sync's transaction, once per configured provider, so a single vendor
     * being unreachable would otherwise pin an open DB transaction for an hour
     * and stall the whole refresh. A model list is a few KB of JSON; if it has
     * not arrived in seconds it is not coming.
     */
    private RestTemplate discoveryRestTemplate() {
        RestTemplate local = discoveryRestTemplate;
        if (local == null) {
            synchronized (discoveryRestTemplateLock) {
                local = discoveryRestTemplate;
                if (local == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(DISCOVERY_CONNECT_TIMEOUT_MS);
                    factory.setReadTimeout(DISCOVERY_READ_TIMEOUT_MS);
                    local = new RestTemplate(factory);
                    discoveryRestTemplate = local;
                }
            }
        }
        return local;
    }

    /**
     * Derive {@code <base>/models} from the configured chat-completions URL.
     * Every provider we register is configured with its full completions path
     * (e.g. {@code https://api.z.ai/api/paas/v4/chat/completions}), and the
     * OpenAI-compatible contract puts {@code /models} as a sibling of
     * {@code /chat/completions}. Returns null when the configured URL does not
     * follow that shape, so an exotic override degrades to "no discovery"
     * rather than to a request against a wrong path.
     */
    String modelsEndpoint() {
        String resolved = resolveConfiguredApiUrl();
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        int idx = resolved.indexOf("/chat/completions");
        if (idx < 0) {
            return null;
        }
        return resolved.substring(0, idx) + "/models";
    }

    /**
     * Pull the ids out of an OpenAI {@code /models} body: {@code {"data":[{"id":…}]}}.
     * Tolerates a bare list and entries that are plain strings - several
     * OpenAI-compatible vendors take liberties with the envelope. Unknown
     * shapes yield an empty list rather than an error.
     */
    @SuppressWarnings("unchecked")
    static List<String> extractModelIds(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }
        Object data = body.get("data");
        if (!(data instanceof List<?> entries)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (entry instanceof String s && !s.isBlank()) {
                ids.add(s.trim());
            } else if (entry instanceof Map<?, ?> map) {
                Object id = ((Map<String, Object>) map).get("id");
                if (id != null && !id.toString().isBlank()) {
                    ids.add(id.toString().trim());
                }
            }
        }
        return ids;
    }

    @Override
    protected HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveApiKey());
        return headers;
    }

    @Override
    protected Map<String, Object> buildRequestBody(CompletionRequest request) {
        Map<String, Object> body = new HashMap<>();

        body.put("model", request.model() != null ? request.model() : getDefaultModel());
        body.put("messages", buildMessages(request));

        body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        if (request.frequencyPenalty() != null) {
            body.put("frequency_penalty", request.frequencyPenalty());
        }
        if (request.presencePenalty() != null) {
            body.put("presence_penalty", request.presencePenalty());
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", buildOpenAITools(request.tools()));
            body.put("tool_choice", "auto");
        }

        return body;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletionResponse parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");

        if (choices == null || choices.isEmpty()) {
            return CompletionResponse.error("No response from " + providerName);
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String finishReason = (String) choice.get("finish_reason");

        String content = message != null ? (String) message.get("content") : null;
        List<Map<String, Object>> toolCalls = message != null
            ? (List<Map<String, Object>>) message.get("tool_calls") : null;

        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        String model = (String) response.get("model");

        return CompletionResponse.builder()
            .content(content != null ? content : "")
            .toolCalls(parseOpenAIToolCalls(toolCalls))
            .finishReason(finishReason)
            .usage(parseUsageInfo(usage))
            .model(model)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected UsageInfo parseUsageInfo(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        UsageInfo.UsageInfoBuilder builder = UsageInfo.builder()
            .promptTokens(getIntValue(usage, "prompt_tokens"))
            .completionTokens(getIntValue(usage, "completion_tokens"))
            .totalTokens(getIntValue(usage, "total_tokens"));

        Object promptDetails = usage.get("prompt_tokens_details");
        if (promptDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) promptDetails;
            builder.cachedTokens(getIntValue(details, "cached_tokens"));
        }

        Object completionDetails = usage.get("completion_tokens_details");
        if (completionDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) completionDetails;
            builder.reasoningTokens(getIntValue(details, "reasoning_tokens"));
        }

        return builder.build();
    }

    @Override
    protected void addStreamingRequestOptions(Map<String, Object> body) {
        body.put("stream_options", Map.of("include_usage", true));
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return null;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode usageNode = node.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                Integer promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
                Integer completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
                Integer totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : null;
                if (promptTokens != null || completionTokens != null) {
                    UsageInfo.UsageInfoBuilder builder = UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(totalTokens);

                    JsonNode promptDetails = usageNode.get("prompt_tokens_details");
                    if (promptDetails != null && !promptDetails.isNull() && promptDetails.has("cached_tokens")) {
                        builder.cachedTokens(promptDetails.get("cached_tokens").asInt());
                    }
                    JsonNode completionDetails = usageNode.get("completion_tokens_details");
                    if (completionDetails != null && !completionDetails.isNull() && completionDetails.has("reasoning_tokens")) {
                        builder.reasoningTokens(completionDetails.get("reasoning_tokens").asInt());
                    }

                    return builder.build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting {} streaming usage: {}", providerName, e.getMessage());
        }

        return null;
    }

    @Override
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("tool_calls")) {
                    JsonNode toolCallsNode = delta.get("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            int index = tc.has("index") ? tc.get("index").asInt() : 0;

                            StreamingToolCallAccumulator acc = accumulators.computeIfAbsent(
                                index, k -> new StreamingToolCallAccumulator());

                            if (tc.has("id")) {
                                acc.id = tc.get("id").asText();
                            }

                            JsonNode function = tc.get("function");
                            if (function != null) {
                                if (function.has("name")) {
                                    acc.name = function.get("name").asText();
                                }
                                if (function.has("arguments")) {
                                    acc.arguments.append(function.get("arguments").asText());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error accumulating {} streaming tool calls: {}", providerName, e.getMessage());
        }
    }

    @Override
    protected String processStreamingLine(String line) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return null;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content")) {
                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (content != null && !content.isEmpty()) {
                            return content;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Failed to parse streaming line from {}: {}", providerName, e.getMessage());
        }

        return null;
    }
}
