package com.multimodalAgent.agent.service.agentruntime;

/**
 * Public, privacy-safe events emitted by an Agent run.
 *
 * <p>Tool arguments, raw tool results, model reasoning and internal risk details are never
 * carried by this contract.</p>
 */
public record AgentEvent(
        String type,
        String runId,
        String content,
        String toolName,
        String status,
        boolean policyEnforced
) {

    public static final String META = "meta";
    public static final String STATUS = "status";
    public static final String TOOL_START = "tool_start";
    public static final String TOOL_RESULT = "tool_result";
    public static final String MODEL_CALL = "model_call";
    public static final String TOKEN = "token";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public AgentEvent {
        type = type == null ? ERROR : type.trim();
        runId = runId == null ? "" : runId.trim();
        content = content == null ? "" : content;
        toolName = toolName == null ? "" : toolName.trim();
        status = status == null ? "" : status.trim();
    }

    public static AgentEvent meta(String runId) {
        return new AgentEvent(META, runId, "", "", "", false);
    }

    public static AgentEvent status(String runId, String content, boolean policyEnforced) {
        return new AgentEvent(STATUS, runId, content, "", "", policyEnforced);
    }

    public static AgentEvent toolStart(String runId, String toolName) {
        return new AgentEvent(TOOL_START, runId, "", toolName, "", false);
    }

    public static AgentEvent toolResult(
            String runId,
            String toolName,
            String status,
            String publicSummary
    ) {
        boolean policyEnforced = "denied".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status);
        return new AgentEvent(TOOL_RESULT, runId, publicSummary, toolName, status, policyEnforced);
    }

    public static AgentEvent modelCall(String runId) {
        return new AgentEvent(MODEL_CALL, runId, "", "", "", true);
    }

    public static AgentEvent token(String runId, String content) {
        return new AgentEvent(TOKEN, runId, content, "", "", false);
    }

    public static AgentEvent done(String runId) {
        return new AgentEvent(DONE, runId, "", "", "", false);
    }

    public static AgentEvent error(String runId, String content, String status) {
        return new AgentEvent(ERROR, runId, content, "", status, false);
    }
}
