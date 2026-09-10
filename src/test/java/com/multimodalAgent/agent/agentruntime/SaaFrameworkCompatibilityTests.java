package com.multimodalAgent.agent.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

class SaaFrameworkCompatibilityTests {

    @Test
    void reactAgentCompletesARealToolLoopWithAChatModel() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback lookup = FunctionToolCallback
                .<String, String>builder("lookup_support", query -> {
                    toolCalls.incrementAndGet();
                    return "support result for " + query;
                })
                .description("Looks up a support answer for a query")
                .inputType(String.class)
                .build();

        ReactAgent agent = ReactAgent.builder()
                .name("compatibility-probe")
                .model(new ScriptedToolCallingChatModel())
                .systemPrompt("Use lookup_support when the user asks for support information.")
                .tools(lookup)
                .build();

        AssistantMessage answer = agent.call("Where can I find support?");

        assertThat(answer.getText()).isEqualTo("The tool says: support result for support");
        assertThat(toolCalls).hasValue(1);
    }

    private static final class ScriptedToolCallingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean hasToolResult = prompt.getInstructions().stream()
                    .anyMatch(message -> message.getMessageType().getValue().equals("tool"));
            AssistantMessage message = hasToolResult
                    ? new AssistantMessage("The tool says: support result for support")
                    : AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-1", "function", "lookup_support", "\"support\"")))
                            .build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }
}
