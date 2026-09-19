package com.multimodalAgent.agent.service.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ContextBudgetServiceTests {

    @Test
    void removesOldConversationMessagesAndPreservesSystemRuntimeAndCurrentInput() {
        multimodalAgentProperties properties = properties(140);
        ContextBudgetService service = new ContextBudgetService(properties);
        List<AiMessage> messages = List.of(
                AiMessage.system("安全规则"),
                AiMessage.user("运行时资料"),
                AiMessage.user("较早历史一".repeat(18)),
                AiMessage.assistant("较早回答".repeat(18)),
                AiMessage.user("当前输入"));

        ContextBudgetService.AiMessageFit fit = service.fitAiMessages(messages);

        assertThat(fit.compacted()).isTrue();
        assertThat(fit.messages()).containsExactly(
                AiMessage.system("安全规则"),
                AiMessage.user("运行时资料"),
                AiMessage.user("当前输入"));
        assertThat(fit.omittedMessageCount()).isEqualTo(2);
        assertThat(fit.estimatedInputTokens()).isLessThanOrEqualTo(fit.inputCeiling());
    }

    @Test
    void trimsEmbeddedHistoryBeforeCurrentInputWithoutTruncatingCurrentInput() {
        multimodalAgentProperties properties = properties(180);
        ContextBudgetService service = new ContextBudgetService(properties);
        String history = "最近上下文：\n" + "旧消息".repeat(1000);
        String current = "\n\n当前输入：\n请保留这句当前问题";
        List<AiMessage> messages = List.of(
                AiMessage.system("路由规则"),
                AiMessage.user(history + current));

        ContextBudgetService.AiMessageFit fit = service.fitAiMessages(messages);

        assertThat(fit.compacted()).isTrue();
        assertThat(fit.messages().get(1).content()).endsWith(current);
        assertThat(fit.estimatedInputTokens()).isLessThanOrEqualTo(fit.inputCeiling());
    }

    @Test
    void rejectsWhenMandatoryCurrentMessageCannotFit() {
        multimodalAgentProperties properties = properties(100);
        ContextBudgetService service = new ContextBudgetService(properties);

        assertThatThrownBy(() -> service.fitAiMessages(List.of(
                AiMessage.system("规则"),
                AiMessage.user("当前输入".repeat(500)))))
                .isInstanceOf(ContextBudgetExceededException.class);
    }

    @Test
    void preservesSpringPromptOptionsWhenItCompactsHistory() {
        multimodalAgentProperties properties = properties(140);
        ContextBudgetService service = new ContextBudgetService(properties);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("安全规则"),
                new UserMessage("运行时资料"),
                new UserMessage("旧消息".repeat(40)),
                new UserMessage("当前输入")));

        ContextBudgetService.PromptFit fit = service.fitPrompt(prompt);

        assertThat(fit.compacted()).isTrue();
        assertThat(fit.prompt().getInstructions()).extracting(message -> message.getText())
                .containsExactly("安全规则", "运行时资料", "当前输入");
        assertThat(fit.estimatedInputTokens()).isLessThanOrEqualTo(fit.inputCeiling());
    }

    private multimodalAgentProperties properties(int contextWindow) {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getAi().setContextWindow(contextWindow);
        properties.getAi().setMaxTokens(0);
        properties.getChat().setContextSafetyMarginTokens(0);
        return properties;
    }
}
