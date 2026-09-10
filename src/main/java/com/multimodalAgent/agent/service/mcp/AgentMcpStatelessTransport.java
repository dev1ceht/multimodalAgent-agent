package com.multimodalAgent.agent.service.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Small adapter that lets the official MCP SDK own protocol dispatch while Spring WebFlux
 * remains the HTTP transport. The SDK's stateless handler receives a fresh context per call.
 */
final class AgentMcpStatelessTransport implements McpStatelessServerTransport {

    private volatile McpStatelessServerHandler handler;

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    Mono<JSONRPCResponse> handleRequest(McpTransportContext context, JSONRPCRequest request) {
        McpStatelessServerHandler currentHandler = handler;
        if (currentHandler == null) {
            return Mono.error(new IllegalStateException("MCP server handler is not initialized"));
        }
        return currentHandler.handleRequest(context, request);
    }

    Mono<Void> handleNotification(McpTransportContext context, JSONRPCNotification notification) {
        McpStatelessServerHandler currentHandler = handler;
        if (currentHandler == null) {
            return Mono.error(new IllegalStateException("MCP server handler is not initialized"));
        }
        return currentHandler.handleNotification(context, notification);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    /**
     * Per-request client transport used by the Agent. It intentionally carries the identity
     * in a request-scoped context instead of a shared mutable field or model argument.
     */
    static final class Client implements io.modelcontextprotocol.spec.McpClientTransport {

        private final AgentMcpStatelessTransport serverTransport;
        private final McpTransportContext context;
        private volatile Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> receiver;

        Client(AgentMcpStatelessTransport serverTransport, McpTransportContext context) {
            this.serverTransport = serverTransport;
            this.context = context;
        }

        @Override
        public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> receiver) {
            this.receiver = Objects.requireNonNull(receiver, "receiver");
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(JSONRPCMessage message) {
            if (message instanceof JSONRPCRequest request) {
                return serverTransport.handleRequest(context, request)
                        .flatMap(response -> deliver(response))
                        .then();
            }
            if (message instanceof JSONRPCNotification notification) {
                return serverTransport.handleNotification(context, notification);
            }
            return Mono.error(new IllegalArgumentException("Unsupported MCP message type"));
        }

        private Mono<JSONRPCMessage> deliver(JSONRPCMessage response) {
            Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> currentReceiver = receiver;
            if (currentReceiver == null) {
                return Mono.error(new IllegalStateException("MCP client is not connected"));
            }
            return currentReceiver.apply(Mono.just(response));
        }

        @Override
        public <T> T unmarshalFrom(Object value, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
            return io.modelcontextprotocol.json.McpJsonDefaults.getMapper().convertValue(value, typeRef);
        }

        @Override
        public List<String> protocolVersions() {
            return List.of("2025-03-26");
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }
}
