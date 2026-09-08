package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classification execution - delegates to {@link AgentLoopService} in single-shot mode
 * (no tools, 1 iteration) so that budget guards, token tracking, and observability
 * are centralized with the general agent pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    static final String SYSTEM_PROMPT = """
        You are a classification assistant. Your ONLY task is to categorize content \
        into one of the provided categories.

        OUTPUT FORMAT - MANDATORY:
        You MUST respond with EXACTLY one raw JSON object. Nothing else.
        No preamble, no explanation, no markdown fences, no trailing text.
        Any output that is not a single valid JSON object is a fatal error.

        JSON schema (strict):
        {
          "selected_category": "category_label",
          "confidence": 0.95,
          "reasoning": "Brief explanation of why this category was chosen"
        }

        Constraints:
        1. selected_category MUST be exactly one of the provided category labels (case-sensitive)
        2. confidence MUST be a number between 0.0 and 1.0
        3. reasoning MUST be a concise single sentence
        4. Output MUST start with { and end with } - no other characters allowed
        """;

    private final AgentLoopService agentLoopService;
    private final GuardChainFactory guardChainFactory;
    private final ObjectMapper objectMapper;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;
    private final ExecutionLinkRouter executionLinkRouter;

    /**
     * Activity source reported for link resolution. Classify requests are produced by
     * one caller only, the workflow classify node, so a {@code WORKFLOW}-scoped link
     * targets them exactly; an {@code ALL} link applies as it does everywhere else.
     */
    static final String ACTIVITY_SOURCE = "WORKFLOW";

    /**
     * Back-compat overload - async paths (queue worker) call without an inbound
     * role context. Falls through to {@link #execute(ClassifyRequestDto, String)}
     * with null roles; downstream the bridge guard treats null as USER.
     */
    public ClassifyResponseDto execute(ClassifyRequestDto request) {
        return execute(request, null);
    }

    public ClassifyResponseDto execute(ClassifyRequestDto request, String userRoles) {
        long startTime = System.currentTimeMillis();
        // Normalise provider against the catalog FIRST: a bridge (CLI) model
        // stored as provider="anthropic" (frontend heuristic / LLM-authored
        // plan) must resolve to its bridge slug so it dispatches via the bridge
        // AND passes through BridgeAccessGuard - identical to the chat path.
        String providerName = modelCatalogService.resolveProvider(request.provider(), request.model());

        String userPrompt = buildPrompt(request);

        try {
            // The budget guard prices the BILLED pair: a link changes where the run
            // executes, never what the user is charged.
            PreIterationGuard guard = guardChainFactory.forAgent(
                request.tenantId(), request.agentEntityId(),
                providerName, request.model());

            // Model execution link: the billed pair may have to run on another target
            // (a CLI bridge, or another API provider). Without this the node would call
            // the billed provider's own API key, i.e. exactly the key an admin linked
            // away from - the failure shape being an upstream billing error on a key
            // the platform deliberately stopped using.
            var route = executionLinkRouter.runnableRoute(providerName, request.model(), ACTIVITY_SOURCE);
            String execProvider = route != null ? route.executionProvider() : providerName;
            String execModel = route != null ? route.executionModel() : request.model();

            boolean useBridge = bridgeDispatcher.shouldDispatch(execProvider);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider(execProvider)
                .model(execModel)
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .tools(null)
                .autoDiscoverTools(false)
                .maxIterations(1)
                .temperature(request.temperature() != null ? request.temperature() : 0.1)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : 500)
                .tenantId(request.tenantId())
                .userRoles(userRoles)
                .agentId(request.agentEntityId())
                .preIterationGuard(guard)
                // EVERY bridge run of this node enters restricted "API mode", linked or
                // not: an empty cwd and none of the CLI's native tools. A single-shot
                // judge that must answer with one JSON object has no use for a source
                // checkout, and without the marker the CLI keeps the repo cwd plus the
                // repo/shell MCP tools, which run arbitrary commands in that checkout.
                // On the direct-API path this node has no tools at all, so restricting is
                // what makes the two transports agree.
                .credentials(useBridge
                    ? Map.of(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, (Object) Boolean.TRUE)
                    : null)
                .purpose(CallPurpose.CLASSIFY)
                .build();

            log.info("Executing classify via {}: billed={}/{}, exec={}/{}, linked={}, categories={}",
                useBridge ? "bridge" : "agent loop",
                providerName, request.model(), execProvider, execModel, route != null,
                request.categories() != null ? request.categories().size() : 0);

            AgentLoopResult result = useBridge
                ? bridgeDispatcher.execute(context)
                : agentLoopService.execute(context, null);

            // Re-stamp the BILLED model ONLY when a link moved the run, mirroring the
            // agent path (which relabels solely on a link). Do not read this as cosmetic:
            // the node output shows it, and on the ASYNC completion path the orchestrator
            // takes the ledger's provider/model from the RESULT first, falling back to the
            // node config - so without the re-stamp a linked run would be charged as the
            // execution target. An UNLINKED bridge run keeps reporting the model id the CLI
            // returned, exactly as before.
            return parseResponse(result, System.currentTimeMillis() - startTime, providerName,
                route != null ? request.model() : null,
                SYSTEM_PROMPT, userPrompt, result.conversationHistory());

        } catch (BridgeAccessDeniedException e) {
            // Propagate so GlobalExceptionHandler maps reason → 403/429. Must come
            // before the Exception catch, which would otherwise squash the denial
            // into a generic 200/FAILED response body.
            log.warn("Classify denied by bridge guard: provider={} reason={}",
                e.getProviderName(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            return new ClassifyResponseDto(false, null, 0, null,
                "Classification error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName, null, 0, 0, 0,
                null, null, userPrompt);
        }
    }

    private String buildPrompt(ClassifyRequestDto request) {
        StringBuilder sb = new StringBuilder();
        // Use prompt as the primary classification instruction (may already include the content
        // via resolved templates). Only fall back to content if prompt is absent.
        if (request.prompt() != null && !request.prompt().isBlank()) {
            sb.append("## Classification Instruction\n").append(request.prompt()).append("\n\n");
        } else if (request.content() != null && !request.content().isBlank()) {
            sb.append("## Content to Classify\n").append(request.content()).append("\n\n");
        }
        sb.append("## Available Categories\n");
        if (request.categories() != null) {
            for (ClassifyRequestDto.CategoryDto category : request.categories()) {
                sb.append("- **").append(category.label()).append("**: ");
                sb.append(category.description() != null ? category.description() : "No description");
                sb.append("\n");
            }
        }
        sb.append("\nClassify the content into ONE of the above categories.");
        return sb.toString();
    }

    /**
     * @param provider    the BILLED provider (never the execution target of a link)
     * @param billedModel the BILLED model, set ONLY when a model execution link moved the
     *                    run elsewhere: the run then reports and is charged as this model
     *                    rather than the execution target. {@code null} on an unlinked run,
     *                    which keeps the identity the loop reported.
     */
    private ClassifyResponseDto parseResponse(AgentLoopResult result, long duration, String provider,
                                                String billedModel,
                                                String systemPrompt, String userPrompt,
                                                List<Message> conversationHistory) {
        String content = result.content();
        UsageInfo usage = result.usage();
        int tokensUsed = usage != null ? usage.getTotal() : 0;
        int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens() : 0;
        String model = billedModel != null ? billedModel : result.model();
        List<ConversationMessageDto> messages = toConversationMessages(conversationHistory);

        if (!result.success()) {
            return new ClassifyResponseDto(false, null, 0, null,
                result.error(), duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }

        if (content == null || content.isBlank()) {
            return new ClassifyResponseDto(false, null, 0, null,
                "Empty response from LLM", duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }
        try {
            String jsonContent = LlmJsonExtractor.extractJson(content);
            Map<String, Object> parsed = objectMapper.readValue(jsonContent, new TypeReference<>() {});
            String selectedCategory = (String) parsed.get("selected_category");
            Number confidenceNum = (Number) parsed.get("confidence");
            String reasoning = (String) parsed.get("reasoning");
            if (selectedCategory == null || selectedCategory.isBlank()) {
                return new ClassifyResponseDto(false, null, 0, null,
                    "No category selected in response", duration, provider, model,
                    tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
            }
            double confidence = confidenceNum != null ? confidenceNum.doubleValue() : 0.5;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            return new ClassifyResponseDto(true, selectedCategory, confidence, reasoning,
                null, duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        } catch (Exception e) {
            log.warn("Failed to parse classify response as JSON, trying plain text: {}", e.getMessage());
            return parseFromPlainText(content, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, userPrompt, messages);
        }
    }

    private ClassifyResponseDto parseFromPlainText(String content, long duration,
                                                     String provider, String model,
                                                     int tokensUsed, int promptTokens,
                                                     int completionTokens,
                                                     String systemPrompt, String userPrompt,
                                                     List<ConversationMessageDto> messages) {
        Pattern pattern = Pattern.compile(
            "(?:category|selected|classification)[:\\s]+[\"']?([\\w\\s-]+)[\"']?",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String category = matcher.group(1).trim();
            return new ClassifyResponseDto(true, category, 0.5,
                "Extracted from plain text response", null, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
        }
        return new ClassifyResponseDto(false, null, 0, null,
            "Could not parse classification response", duration, provider, model,
            tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
    }

    /**
     * Convert agent loop conversation history to lightweight DTOs for transport.
     */
    static List<ConversationMessageDto> toConversationMessages(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
            .map(m -> new ConversationMessageDto(
                m.role() != null ? m.role().name() : "USER",
                m.content(),
                m.toolCallId(),
                m.toolName()))
            .toList();
    }
}
