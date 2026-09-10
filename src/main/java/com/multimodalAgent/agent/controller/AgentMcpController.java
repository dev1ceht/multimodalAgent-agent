package com.multimodalAgent.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.mcp.AgentMcpSdkServer;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Standard MCP JSON-RPC endpoint reserved for the Agent runtime. */
@RestController
public final class AgentMcpController {

    private final AgentMcpSdkServer server;

    public AgentMcpController(AgentMcpSdkServer server) {
        this.server = server;
    }

    @PostMapping(
            value = "/agent-mcp",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> handle(
            @RequestBody JsonNode request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return server.handleAsync(request, currentUser);
    }
}
