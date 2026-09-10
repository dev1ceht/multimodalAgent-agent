package com.multimodalAgent.agent.service.agentruntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.definition.DefaultToolDefinition.Builder;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Small Spring AI callback adapter that keeps parsing, authorization and budget checks in one
 * place. ReactAgent owns the loop; this adapter only executes one already-selected tool call.
 */
public final class JsonAgentToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final AgentRunContext context;
    private final AgentToolPolicy toolPolicy;
    private final AgentBudgetPolicy budgetPolicy;
    private final ObjectMapper objectMapper;
    private final Function<JsonNode, AgentToolResult> handler;
    private final int maxResultCharacters;

    public JsonAgentToolCallback(
            String name,
            String description,
            String inputSchema,
            AgentRunContext context,
            AgentToolPolicy toolPolicy,
            AgentBudgetPolicy budgetPolicy,
            ObjectMapper objectMapper,
            int maxResultCharacters,
            Function<JsonNode, AgentToolResult> handler
    ) {
        this.definition = toolDefinition(name, description, inputSchema);
        this.context = Objects.requireNonNull(context, "context");
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
        this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maxResultCharacters = Math.max(256, maxResultCharacters);
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String arguments) {
        return call(arguments, null);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        String name = definition.name();
        String rawArguments = arguments == null || arguments.isBlank() ? "{}" : arguments.trim();
        context.addEvent(AgentEvent.toolStart(context.runId(), name));

        AgentBudgetPolicy.BudgetDecision budgetDecision = budgetPolicy.beforeToolCall(
                context.budget(), name, rawArguments);
        if (!budgetDecision.allowed()) {
            return finish(name, AgentToolResult.denied(budgetDecision.errorCode()));
        }

        try {
            JsonNode parsed = objectMapper.readTree(rawArguments);
            AgentToolPolicy.ToolValidation authorization = toolPolicy.authorize(context, name);
            if (!authorization.valid()) {
                return finish(name, AgentToolResult.denied(authorization.errorCode()));
            }
            AgentToolPolicy.ToolValidation validation = toolPolicy.validate(name, parsed);
            if (!validation.valid()) {
                AgentBudgetPolicy.BudgetDecision repair = validation.retryable()
                        ? budgetPolicy.recordArgumentRepair(context.budget())
                        : AgentBudgetPolicy.BudgetDecision.deny(
                                validation.errorCode(), validation.publicMessage());
                String code = repair.allowed() ? validation.errorCode() : repair.errorCode();
                return finish(name, AgentToolResult.denied(code));
            }
            AgentToolResult result = handler.apply(parsed);
            return finish(name, result == null
                    ? AgentToolResult.failed("tool_failed", true)
                    : result);
        } catch (JsonProcessingException exception) {
            AgentBudgetPolicy.BudgetDecision repair = budgetPolicy.recordArgumentRepair(context.budget());
            return finish(name, AgentToolResult.denied(
                    repair.allowed() ? "invalid_tool_arguments" : repair.errorCode()));
        } catch (RuntimeException exception) {
            return finish(name, AgentToolResult.failed("tool_failed", true));
        }
    }

    private String finish(String name, AgentToolResult result) {
        AgentToolResult safeResult = result == null ? AgentToolResult.failed("tool_failed", true) : result;
        String serialized = safeResult.toJson(objectMapper);
        if (serialized.length() > maxResultCharacters) {
            safeResult = AgentToolResult.failed("tool_result_too_large", false);
            serialized = safeResult.toJson(objectMapper);
        }
        if (!budgetPolicy.recordToolResult(context.budget(), serialized.length())) {
            safeResult = AgentToolResult.failed("tool_result_budget_exceeded", false);
            serialized = safeResult.toJson(objectMapper);
        }
        context.addEvent(AgentEvent.toolResult(
                context.runId(),
                name,
                safeResult.status(),
                publicSummary(safeResult)));
        return serialized;
    }

    private static String publicSummary(AgentToolResult result) {
        if (result.errorCode() == null || result.errorCode().isBlank()) {
            return result.status();
        }
        return result.status() + ":" + result.errorCode();
    }

    private static ToolDefinition toolDefinition(String name, String description, String inputSchema) {
        Builder builder = ToolDefinition.builder()
                .name(name)
                .description(description);
        return builder.inputSchema(inputSchema).build();
    }
}
