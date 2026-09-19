package com.multimodalAgent.agent.service.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.ai.AiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContextSummaryCompilerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsTraceableSummaryAndCountsItsBoundedJson() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.completeJson(any(), any())).thenReturn(summary("用户希望调整作息", 7));
        multimodalAgentProperties properties = new multimodalAgentProperties();
        ContextSummaryCompiler compiler = new ContextSummaryCompiler(
                aiClient, objectMapper, new PrivacySanitizer(), new ContextTokenEstimator(), properties);

        ContextSummaryCompiler.CompiledSummary result = compiler.compile("{}", List.of(message(7L)));

        assertThat(result.json()).contains("conversation-summary-v1", "调整作息");
        assertThat(result.tokenCount()).isPositive();
    }

    @Test
    void rejectsSourceIdsThatAreNotInTheCurrentBatchOrPreviousSummary() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.completeJson(any(), any())).thenReturn(summary("越权来源", 999));
        multimodalAgentProperties properties = new multimodalAgentProperties();
        ContextSummaryCompiler compiler = new ContextSummaryCompiler(
                aiClient, objectMapper, new PrivacySanitizer(), new ContextTokenEstimator(), properties);

        assertThatThrownBy(() -> compiler.compile("{}", List.of(message(7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("summary_source_invalid");
    }

    @Test
    void rejectsUnknownRootFieldsAndEmptyTraceability() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.completeJson(any(), any())).thenReturn(summary("无来源", 7)
                .replace("\"sourceMessageIds\":[7]", "\"sourceMessageIds\":[]")
                .replace("\"corrections\":[]", "\"corrections\":[],\"unexpected\":true"));
        multimodalAgentProperties properties = new multimodalAgentProperties();
        ContextSummaryCompiler compiler = new ContextSummaryCompiler(
                aiClient, objectMapper, new PrivacySanitizer(), new ContextTokenEstimator(), properties);

        assertThatThrownBy(() -> compiler.compile("{}", List.of(message(7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("summary_field_invalid:");
    }

    private ChatMessage message(Long id) {
        ChatMessage message = new ChatMessage();
        ReflectionTestUtils.setField(message, "id", id);
        message.setRole(MessageRole.USER);
        message.setContent("我最近睡得比较晚");
        return message;
    }

    private String summary(String text, long sourceId) {
        return """
                {
                  "schemaVersion":"conversation-summary-v1",
                  "topics":[],
                  "userGoals":[{"text":"%s","sourceMessageIds":[%d]}],
                  "constraints":[],
                  "events":[],
                  "attemptedActions":[],
                  "assistantSuggestions":[],
                  "openQuestions":[],
                  "corrections":[]
                }
                """.formatted(text, sourceId);
    }
}
