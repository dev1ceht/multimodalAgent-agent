package com.multimodalAgent.agent.service.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextLongDialogueAcceptanceTests {

    @Test
    void comparesWindowBudgetAndSummaryAcrossFiftySyntheticLongDialogues() {
        int evaluated = 0;
        int windowOverflows = 0;
        for (int scenario = 0; scenario < 50; scenario++) {
            int turns = 30 + (scenario * 37) % 71;
            String current = "当前问题-" + scenario;
            List<AiMessage> raw = dialogue(turns, current);

            ContextBudgetService.AiMessageFit window =
                    service("window").fitAiMessages(raw);
            ContextBudgetService.AiMessageFit budget =
                    service("budget").fitAiMessages(raw);
            ContextBudgetService.AiMessageFit summary =
                    service("summary").fitAiMessages(summaryView(raw, current));

            if (window.estimatedInputTokens() > window.inputCeiling()) {
                windowOverflows++;
            }
            assertRequestShape(window, current);
            assertSafeRequest(budget, current);
            assertSafeRequest(summary, current);
            assertThat(summary.messages()).anySatisfy(message ->
                    assertThat(message.content()).contains("早期约束：周三晚不能安排任务", "后续纠正：改为周四晚"));
            evaluated++;
        }

        assertThat(evaluated).isEqualTo(50);
        assertThat(windowOverflows).isEqualTo(50);
    }

    private void assertSafeRequest(ContextBudgetService.AiMessageFit fit, String current) {
        assertThat(fit.estimatedInputTokens()).isLessThanOrEqualTo(fit.inputCeiling());
        assertRequestShape(fit, current);
    }

    private void assertRequestShape(ContextBudgetService.AiMessageFit fit, String current) {
        assertThat(fit.messages().get(0).role()).isEqualTo("system");
        assertThat(fit.messages().stream()
                .filter(message -> current.equals(message.content()))
                .count()).isOne();
    }

    private List<AiMessage> dialogue(int turns, String current) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system("安全规则与回答格式"));
        messages.add(AiMessage.user("运行时资料：本轮身份与权限已验证"));
        for (int turn = 0; turn < turns; turn++) {
            String detail = turn == 0
                    ? "早期约束：周三晚不能安排任务"
                    : turn == turns / 2
                            ? "后续纠正：改为周四晚"
                            : "第" + turn + "轮进展与待办";
            messages.add(AiMessage.user(detail + "，补充说明".repeat(8)));
            messages.add(AiMessage.assistant(
                    (turn % 5 == 0 ? "工具结果{\"evidenceId\":" + turn + "}；" : "")
                            + "建议与未解决问题".repeat(8)));
        }
        messages.add(AiMessage.user(current));
        return List.copyOf(messages);
    }

    private List<AiMessage> summaryView(List<AiMessage> raw, String current) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(raw.get(0));
        messages.add(raw.get(1));
        messages.add(AiMessage.user(
                "【会话历史摘要】\n早期约束：周三晚不能安排任务；后续纠正：改为周四晚；仍需确认优先级。"));
        int start = Math.max(2, raw.size() - 9);
        messages.addAll(raw.subList(start, raw.size() - 1));
        messages.add(AiMessage.user(current));
        return List.copyOf(messages);
    }

    private ContextBudgetService service(String mode) {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getChat().setContextMode(mode);
        properties.getAi().setContextWindow(1600);
        properties.getAi().setMaxTokens(200);
        properties.getChat().setContextSafetyMarginTokens(100);
        return new ContextBudgetService(properties);
    }
}
