package com.multimodalAgent.agent.service.agentruntime;

import reactor.core.publisher.Flux;

/** Public seam for the student-facing Agent execution loop. */
public interface MindCareAgentRuntime {

    Flux<AgentEvent> run(AgentRequest request);
}
