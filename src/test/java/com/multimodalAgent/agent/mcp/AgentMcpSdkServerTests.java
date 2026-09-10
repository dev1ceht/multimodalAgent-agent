package com.multimodalAgent.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.service.ConsentService;
import com.multimodalAgent.agent.service.RiskCaseService;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import com.multimodalAgent.agent.service.mcp.AgentMcpSdkServer;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.modelcontextprotocol.client.McpSyncClient;

class AgentMcpSdkServerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sdkOwnsInitializeListAndCallLifecycle() throws Exception {
        ConsentService consentService = mock(ConsentService.class);
        RiskCaseService riskCaseService = mock(RiskCaseService.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        CurrentUser user = student();
        when(riskCaseService.studentSupportStatus(any(CurrentUser.class)))
                .thenReturn(List.of(new StudentSupportStatusResponse(
                        42L, null, true, Instant.parse("2026-09-10T00:00:00Z"))));
        AgentMcpSdkServer server = new AgentMcpSdkServer(
                objectMapper, consentService, riskCaseService, userRepository);

        Map<String, Object> initialize = server.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-03-26","capabilities":{},
                  "clientInfo":{"name":"test-client","version":"1.0"}}}
                """), user);
        assertThat(initialize).containsEntry("jsonrpc", "2.0");
        assertThat(initialize.get("result")).isInstanceOf(Map.class);

        assertThat(server.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """), user)).isEmpty();

        Map<String, Object> list = server.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """), user);
        Map<?, ?> listResult = (Map<?, ?>) list.get("result");
        assertThat(listResult.get("tools")).asList()
                .singleElement()
                .extracting("name")
                .isEqualTo(AgentMcpSdkServer.SUPPORT_STATUS_TOOL);

        Map<String, Object> call = server.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"get_support_status","arguments":{}}}
                """), user);
        Map<?, ?> callResult = (Map<?, ?>) call.get("result");
        assertThat(callResult.get("isError")).isEqualTo(false);
        assertThat(callResult.get("content").toString()).contains("42");
        verify(consentService).requireChatConsent(user);
        verify(riskCaseService).studentSupportStatus(user);
    }

    @Test
    void sdkClientUsesPerRequestIdentityAndReturnsToolIsError() {
        ConsentService consentService = mock(ConsentService.class);
        RiskCaseService riskCaseService = mock(RiskCaseService.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        UserAccount account = account("student-a");
        when(userRepository.findById(7L)).thenReturn(Optional.of(account));
        when(riskCaseService.studentSupportStatus(any(CurrentUser.class))).thenReturn(List.of());
        AgentMcpSdkServer server = new AgentMcpSdkServer(
                objectMapper, consentService, riskCaseService, userRepository);

        McpSyncClient client = server.openScopedClient(
                new ConversationIdentity(7L, 8L, "public-session", "student-a"));
        try {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(tool -> tool.name())
                    .containsExactly(AgentMcpSdkServer.SUPPORT_STATUS_TOOL);
            assertThat(client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    AgentMcpSdkServer.SUPPORT_STATUS_TOOL, Map.of())).isError()).isFalse();
        } finally {
            client.close();
        }
        verify(riskCaseService).studentSupportStatus(any(CurrentUser.class));
    }

    @Test
    void twoScopedClientsRemainIsolatedWhenCallingConcurrently() throws Exception {
        ConsentService consentService = mock(ConsentService.class);
        RiskCaseService riskCaseService = mock(RiskCaseService.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(account("student-a")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(account("student-b")));
        when(riskCaseService.studentSupportStatus(any(CurrentUser.class))).thenAnswer(invocation -> {
            CurrentUser currentUser = invocation.getArgument(0);
            long caseId = "student-a".equals(currentUser.getUsername()) ? 701L : 802L;
            return List.of(new StudentSupportStatusResponse(
                    caseId, null, true, Instant.parse("2026-09-10T00:00:00Z")));
        });
        AgentMcpSdkServer server = new AgentMcpSdkServer(
                objectMapper, consentService, riskCaseService, userRepository);

        McpSyncClient clientA = server.openScopedClient(
                new ConversationIdentity(7L, 8L, "session-a", "student-a"));
        McpSyncClient clientB = server.openScopedClient(
                new ConversationIdentity(8L, 9L, "session-b", "student-b"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            clientA.initialize();
            clientB.initialize();
            Future<io.modelcontextprotocol.spec.McpSchema.CallToolResult> resultA = executor.submit(
                    () -> clientA.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                            AgentMcpSdkServer.SUPPORT_STATUS_TOOL, Map.of())));
            Future<io.modelcontextprotocol.spec.McpSchema.CallToolResult> resultB = executor.submit(
                    () -> clientB.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                            AgentMcpSdkServer.SUPPORT_STATUS_TOOL, Map.of())));

            assertThat(resultA.get(5, TimeUnit.SECONDS).isError()).isFalse();
            assertThat(resultB.get(5, TimeUnit.SECONDS).isError()).isFalse();
            ArgumentCaptor<CurrentUser> users = ArgumentCaptor.forClass(CurrentUser.class);
            verify(riskCaseService, times(2)).studentSupportStatus(users.capture());
            assertThat(users.getAllValues()).extracting(CurrentUser::getUsername)
                    .containsExactlyInAnyOrder("student-a", "student-b");
        } finally {
            clientA.close();
            clientB.close();
            executor.shutdownNow();
        }
    }

    @Test
    void unauthorizedSdkCallIsToolErrorAndDoesNotReachBusinessService() throws Exception {
        ConsentService consentService = mock(ConsentService.class);
        RiskCaseService riskCaseService = mock(RiskCaseService.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        AgentMcpSdkServer server = new AgentMcpSdkServer(
                objectMapper, consentService, riskCaseService, userRepository);

        Map<String, Object> response = server.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":"denied","method":"tools/call","params":{
                  "name":"get_support_status","arguments":{}}}
                """), null);
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertThat(result.get("isError")).isEqualTo(true);
        verify(riskCaseService, org.mockito.Mockito.never()).studentSupportStatus(any());
    }

    private static CurrentUser student() {
        return new CurrentUser(account("student-a"));
    }

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword("not-used");
        account.setDisplayName("Student");
        account.setRoles(Set.of("ROLE_USER"));
        return account;
    }
}
