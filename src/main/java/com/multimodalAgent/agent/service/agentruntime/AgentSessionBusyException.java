package com.multimodalAgent.agent.service.agentruntime;

/** Raised when one student tries to start a second run in the same conversation session. */
public final class AgentSessionBusyException extends RuntimeException {

    public AgentSessionBusyException() {
        super("agent_session_busy");
    }
}
