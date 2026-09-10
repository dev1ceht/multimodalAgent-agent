package com.multimodalAgent.agent.service.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ConsentService;
import com.multimodalAgent.agent.service.RiskCaseService;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Official Java MCP SDK server for the Agent-only endpoint.
 *
 * <p>The server is stateless. Authentication and conversation identity are supplied through
 * a per-request transport context and are never stored in a static or shared user field.</p>
 */
@Component
public final class AgentMcpSdkServer {

    public static final String CURRENT_USER_CONTEXT_KEY = "currentUser";
    public static final String CONVERSATION_IDENTITY_CONTEXT_KEY = "conversationIdentity";
    public static final String SUPPORT_STATUS_TOOL = "get_support_status";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final ConsentService consentService;
    private final RiskCaseService riskCaseService;
    private final UserAccountRepository userAccountRepository;
    private final AgentMcpStatelessTransport transport;
    private final McpStatelessSyncServer server;

    public AgentMcpSdkServer(
            ObjectMapper objectMapper,
            ConsentService consentService,
            RiskCaseService riskCaseService,
            UserAccountRepository userAccountRepository
    ) {
        this.objectMapper = objectMapper;
        this.consentService = consentService;
        this.riskCaseService = riskCaseService;
        this.userAccountRepository = userAccountRepository;
        this.transport = new AgentMcpStatelessTransport();

        McpSchema.Tool supportStatusTool = McpSchema.Tool.builder()
                .name(SUPPORT_STATUS_TOOL)
                .description("Read the authenticated student's own support case status.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(),
                        List.of(),
                        false,
                        Map.of(),
                        Map.of()))
                .annotations(new McpSchema.ToolAnnotations(
                        "Read support status",
                        true,
                        false,
                        true,
                        false,
                        false))
                .build();

        this.server = McpServer.sync(transport)
                .serverInfo(new Implementation("mindcare-agent-mcp", "1.0.0"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(supportStatusTool, this::callSupportStatus)
                .build();
    }

    public Map<String, Object> handle(JsonNode request, CurrentUser currentUser) {
        if (request == null || !request.isObject()) {
            return error(null, -32600, "Invalid Request");
        }
        String jsonrpc = text(request, "jsonrpc");
        String method = text(request, "method");
        Object id = request.has("id") && !request.get("id").isNull()
                ? value(request.get("id"))
                : null;
        if (!McpSchema.JSONRPC_VERSION.equals(jsonrpc) || method == null || method.isBlank()) {
            return error(id, -32600, "Invalid Request");
        }
        Map<String, Object> parameters;
        try {
            parameters = params(request);
        } catch (IllegalArgumentException exception) {
            return error(id, -32602, "Invalid params");
        }
        McpTransportContext context = context(currentUser, null);
        if (id == null) {
            JSONRPCNotification notification = new JSONRPCNotification(jsonrpc, method, parameters);
            try {
                transport.handleNotification(context, notification).block(Duration.ofSeconds(10));
            } catch (RuntimeException exception) {
                return Map.of();
            }
            return Map.of();
        }

        JSONRPCRequest sdkRequest = new JSONRPCRequest(jsonrpc, method, id, parameters);
        try {
            JSONRPCResponse response = transport.handleRequest(context, sdkRequest)
                    .block(Duration.ofSeconds(10));
            return responseMap(response);
        } catch (RuntimeException exception) {
            return error(id, -32603, "Internal error");
        }
    }

    /** Non-blocking WebFlux adapter; SDK dispatch is subscribed on the request pipeline. */
    public Mono<Map<String, Object>> handleAsync(JsonNode request, CurrentUser currentUser) {
        if (request == null || !request.isObject()) {
            return Mono.just(error(null, -32600, "Invalid Request"));
        }
        String jsonrpc = text(request, "jsonrpc");
        String method = text(request, "method");
        Object id = request.has("id") && !request.get("id").isNull()
                ? value(request.get("id"))
                : null;
        if (!McpSchema.JSONRPC_VERSION.equals(jsonrpc) || method == null || method.isBlank()) {
            return Mono.just(error(id, -32600, "Invalid Request"));
        }
        Map<String, Object> parameters;
        try {
            parameters = params(request);
        } catch (IllegalArgumentException exception) {
            return Mono.just(error(id, -32602, "Invalid params"));
        }
        McpTransportContext context = context(currentUser, null);
        if (id == null) {
            JSONRPCNotification notification = new JSONRPCNotification(jsonrpc, method, parameters);
            return transport.handleNotification(context, notification)
                    .thenReturn(Map.<String, Object>of())
                    .onErrorResume(exception -> Mono.just(Map.<String, Object>of()));
        }
        JSONRPCRequest sdkRequest = new JSONRPCRequest(jsonrpc, method, id, parameters);
        return transport.handleRequest(context, sdkRequest)
                .map(this::responseMap)
                .onErrorResume(exception -> Mono.just(error(id, -32603, "Internal error")));
    }

    /** Creates a standard SDK client bound to exactly one trusted conversation identity. */
    public McpSyncClient openScopedClient(ConversationIdentity identity) {
        McpTransportContext context = context(null, identity);
        AgentMcpStatelessTransport.Client clientTransport =
                new AgentMcpStatelessTransport.Client(transport, context);
        return McpClient.sync(clientTransport)
                .clientInfo(new Implementation("mindcare-agent", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<McpSchema.Tool> listTools() {
        return server.listTools();
    }

    private CallToolResult callSupportStatus(
            McpTransportContext context,
            CallToolRequest request
    ) {
        if (request.arguments() != null && !request.arguments().isEmpty()) {
            return toolError("invalid_arguments");
        }

        CurrentUser currentUser = currentUser(context);
        if (!isStudent(currentUser)) {
            return toolError("not_authorized");
        }
        try {
            // The MCP server independently validates consent; upstream flags are never trusted.
            consentService.requireChatConsent(currentUser);
        } catch (RuntimeException exception) {
            return toolError("consent_required");
        }

        try {
            List<StudentSupportStatusResponse> statuses = riskCaseService.studentSupportStatus(currentUser);
            List<Map<String, Object>> publicStatuses = statuses == null ? List.of() : statuses.stream()
                    .map(this::publicStatus)
                    .toList();
            return CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(publicStatuses))
                    .isError(false)
                    .build();
        } catch (RuntimeException exception) {
            return toolError("support_status_failed");
        } catch (Exception exception) {
            return toolError("support_status_serialization_failed");
        }
    }

    private Map<String, Object> publicStatus(StudentSupportStatusResponse status) {
        Map<String, Object> publicStatus = new LinkedHashMap<>();
        if (status == null) {
            return publicStatus;
        }
        publicStatus.put("caseId", status.caseId());
        publicStatus.put("status", status.status() == null ? "UNKNOWN" : status.status().name());
        publicStatus.put("hasActiveReferral", status.hasActiveReferral());
        publicStatus.put("updatedAt", status.updatedAt() == null ? "" : status.updatedAt().toString());
        return publicStatus;
    }

    private McpTransportContext context(
            CurrentUser currentUser,
            ConversationIdentity identity
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (currentUser != null) {
            values.put(CURRENT_USER_CONTEXT_KEY, currentUser);
        }
        if (identity != null) {
            values.put(CONVERSATION_IDENTITY_CONTEXT_KEY, identity);
        }
        return McpTransportContext.create(values);
    }

    private CurrentUser currentUser(McpTransportContext context) {
        Object currentUser = context.get(CURRENT_USER_CONTEXT_KEY);
        if (currentUser instanceof CurrentUser user) {
            return user;
        }
        Object identityValue = context.get(CONVERSATION_IDENTITY_CONTEXT_KEY);
        if (!(identityValue instanceof ConversationIdentity identity) || identity.userId() == null) {
            return null;
        }
        UserAccount account = userAccountRepository.findById(identity.userId()).orElse(null);
        if (account == null || !account.isEnabled()) {
            return null;
        }
        return new CurrentUser(account);
    }

    private boolean isStudent(CurrentUser currentUser) {
        if (currentUser == null || currentUser.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = currentUser.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        return UserRole.isStudentAccount(roles);
    }

    private Map<String, Object> responseMap(JSONRPCResponse response) {
        if (response == null) {
            return error(null, -32603, "Empty MCP response");
        }
        Map<String, Object> result = objectMapper.convertValue(response, MAP_TYPE);
        result.entrySet().removeIf(entry -> entry.getValue() == null);
        return result;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        JSONRPCResponse response = new JSONRPCResponse(
                McpSchema.JSONRPC_VERSION,
                id,
                null,
                new JSONRPCResponse.JSONRPCError(code, message, null));
        return responseMap(response);
    }

    private CallToolResult toolError(String code) {
        return CallToolResult.builder()
                .addTextContent(code)
                .isError(true)
                .build();
    }

    private Map<String, Object> params(JsonNode request) {
        JsonNode params = request.get("params");
        if (params == null || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject()) {
            throw new IllegalArgumentException("MCP params must be an object");
        }
        return objectMapper.convertValue(params, MAP_TYPE);
    }

    private Object value(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
