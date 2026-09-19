package com.multimodalAgent.agent.service.context;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * Deep module for fitting provider-neutral and Spring AI messages into one request budget.
 *
 * <p>System messages, the first runtime-data message and the latest message are protected. Older
 * conversational messages are removed as whole messages. A single over-sized mandatory message
 * is rejected instead of silently truncating the current user input.</p>
 */
@Service
public class ContextBudgetService {

    private static final int PROTOCOL_OVERHEAD_TOKENS = 64;

    private final multimodalAgentProperties properties;
    private final ContextTokenEstimator estimator;

    @Autowired
    public ContextBudgetService(multimodalAgentProperties properties) {
        this(properties, new ContextTokenEstimator());
    }

    public ContextBudgetService(multimodalAgentProperties properties, ContextTokenEstimator estimator) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public AiMessageFit fitAiMessages(List<AiMessage> source) {
        List<AiMessage> safe = source == null ? List.of() : List.copyOf(source);
        if (isWindowMode()) {
            return new AiMessageFit(safe, estimator.estimate(safe), inputCeiling(), 0, false);
        }
        int ceiling = inputCeiling();
        int estimated = estimator.estimate(safe) + PROTOCOL_OVERHEAD_TOKENS;
        if (estimated <= ceiling) {
            return new AiMessageFit(safe, estimated, ceiling, 0, false);
        }

        List<AiMessage> fitted = fitMessages(safe, this::estimateAi, this::isProtectedAi,
                this::clipEmbeddedHistory);
        int fittedTokens = estimator.estimate(fitted) + PROTOCOL_OVERHEAD_TOKENS;
        return new AiMessageFit(fitted, fittedTokens, ceiling, safe.size() - fitted.size(), true);
    }

    public PromptFit fitPrompt(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        List<Message> source = List.copyOf(prompt.getInstructions());
        if (isWindowMode()) {
            return new PromptFit(prompt, estimator.estimateSpringMessages(source) + PROTOCOL_OVERHEAD_TOKENS,
                    inputCeiling(), 0, false);
        }
        int ceiling = inputCeiling();
        int estimated = estimator.estimateSpringMessages(source) + PROTOCOL_OVERHEAD_TOKENS;
        if (estimated <= ceiling) {
            return new PromptFit(prompt, estimated, ceiling, 0, false);
        }
        List<Message> fitted = fitSpringMessages(source);
        int fittedTokens = estimator.estimateSpringMessages(fitted) + PROTOCOL_OVERHEAD_TOKENS;
        return new PromptFit(new Prompt(fitted, prompt.getOptions()), fittedTokens, ceiling,
                source.size() - fitted.size(), true);
    }

    public int inputCeiling() {
        int contextWindow = Math.max(1, properties.getAi().getContextWindow());
        int outputReserve = Math.max(0, properties.getAi().getMaxTokens());
        int safety = Math.max(0, properties.getChat().getContextSafetyMarginTokens());
        return Math.max(1, contextWindow - outputReserve - safety);
    }

    public String tokenCounterVersion() {
        return ContextTokenEstimator.VERSION;
    }

    public boolean isSummaryMode() {
        return "summary".equalsIgnoreCase(properties.getChat().getContextMode());
    }

    private boolean isWindowMode() {
        return "window".equalsIgnoreCase(properties.getChat().getContextMode());
    }

    private int estimateAi(AiMessage message) {
        return estimator.estimate(message);
    }

    private int estimateSpring(Message message) {
        return estimator.estimate(message);
    }

    private boolean isProtectedAi(List<AiMessage> messages, int index) {
        AiMessage message = messages.get(index);
        if ("system".equalsIgnoreCase(message.role())
                || (message.content() != null && message.content().contains("【会话历史摘要】"))) {
            return true;
        }
        int firstData = firstNonSystemAi(messages);
        return index == firstData || index == messages.size() - 1;
    }

    private boolean isProtectedSpring(List<Message> messages, int index) {
        Message message = messages.get(index);
        if (message.getMessageType() == MessageType.SYSTEM
                || (message.getText() != null && message.getText().contains("【会话历史摘要】"))) {
            return true;
        }
        int firstData = firstNonSystemSpring(messages);
        return index == firstData || index == messages.size() - 1;
    }

    private int firstNonSystemAi(List<AiMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!"system".equalsIgnoreCase(messages.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    private int firstNonSystemSpring(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageType() != MessageType.SYSTEM) {
                return i;
            }
        }
        return -1;
    }

    private List<Message> fitSpringMessages(List<Message> source) {
        List<Message> fitted = new ArrayList<>(source);
        while (estimator.estimateSpringMessages(fitted) + PROTOCOL_OVERHEAD_TOKENS > inputCeiling()) {
            List<int[]> groups = springGroups(fitted);
            int[] removable = null;
            for (int[] group : groups) {
                boolean protectedGroup = false;
                for (int index = group[0]; index <= group[1]; index++) {
                    if (isProtectedSpring(fitted, index)) {
                        protectedGroup = true;
                        break;
                    }
                }
                if (!protectedGroup) {
                    removable = group;
                    break;
                }
            }
            if (removable == null) {
                throw new ContextBudgetExceededException(
                        estimator.estimateSpringMessages(fitted) + PROTOCOL_OVERHEAD_TOKENS,
                        inputCeiling());
            }
            fitted.subList(removable[0], removable[1] + 1).clear();
        }
        return List.copyOf(fitted);
    }

    private List<int[]> springGroups(List<Message> messages) {
        List<int[]> groups = new ArrayList<>();
        for (int index = 0; index < messages.size();) {
            int end = index;
            Message message = messages.get(index);
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                while (end + 1 < messages.size()
                        && messages.get(end + 1).getMessageType() == MessageType.TOOL) {
                    end++;
                }
            } else if (message.getMessageType() == MessageType.TOOL && index > 0
                    && messages.get(index - 1) instanceof AssistantMessage assistant
                    && assistant.hasToolCalls()) {
                index++;
                continue;
            }
            groups.add(new int[] {index, end});
            index = end + 1;
        }
        return groups;
    }
    private <T> List<T> fitMessages(
            List<T> source,
            java.util.function.ToIntFunction<T> estimate,
            java.util.function.BiPredicate<List<T>, Integer> protectedMessage,
            java.util.function.Function<List<T>, List<T>> normalize
    ) {
        List<T> fitted = new ArrayList<>(source);
        while (estimateWithOverhead(fitted, estimate) > inputCeiling()) {
            int removable = oldestRemovableIndex(fitted, protectedMessage);
            if (removable < 0) {
                List<T> clipped = normalize.apply(fitted);
                if (clipped != fitted && estimateWithOverhead(clipped, estimate) <= inputCeiling()) {
                    fitted = new ArrayList<>(clipped);
                    break;
                }
                throw new ContextBudgetExceededException(
                        estimateWithOverhead(fitted, estimate), inputCeiling());
            }
            fitted.remove(removable);
        }
        return List.copyOf(fitted);
    }

    private <T> int oldestRemovableIndex(
            List<T> messages,
            java.util.function.BiPredicate<List<T>, Integer> protectedMessage
    ) {
        for (int i = 0; i < messages.size(); i++) {
            if (!protectedMessage.test(messages, i)) {
                return i;
            }
        }
        return -1;
    }

    private <T> int estimateWithOverhead(List<T> messages, java.util.function.ToIntFunction<T> estimate) {
        return PROTOCOL_OVERHEAD_TOKENS + messages.stream().mapToInt(estimate).sum();
    }

    private List<AiMessage> clipEmbeddedHistory(List<AiMessage> messages) {
        if (messages.size() != 2) {
            return messages;
        }
        AiMessage candidate = messages.get(1);
        if (candidate.content() == null) {
            return messages;
        }
        String marker = "\n\n当前输入：";
        int markerIndex = candidate.content().indexOf(marker);
        if (markerIndex < 0) {
            return messages;
        }
        String current = candidate.content().substring(markerIndex);
        String prefix = candidate.content().substring(0, markerIndex);
        int available = inputCeiling() - PROTOCOL_OVERHEAD_TOKENS - estimator.estimate(messages.get(0))
                - estimator.estimate(AiMessage.user(current));
        if (available <= 0) {
            return messages;
        }
        String retained = retainSuffixByBudget(prefix, available);
        return List.of(messages.get(0), AiMessage.user(retained + current));
    }

    private String retainSuffixByBudget(String value, int tokenBudget) {
        if (estimator.estimate(AiMessage.user(value)) <= tokenBudget) {
            return value;
        }
        String marker = "最近上下文（较早内容已省略）：\n";
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            String candidate = marker + value.substring(Math.max(0, value.length() - middle));
            if (estimator.estimate(AiMessage.user(candidate)) <= tokenBudget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return marker + value.substring(Math.max(0, value.length() - low));
    }

    public record AiMessageFit(
            List<AiMessage> messages,
            int estimatedInputTokens,
            int inputCeiling,
            int omittedMessageCount,
            boolean compacted
    ) {
        public AiMessageFit {
            messages = List.copyOf(messages);
        }
    }

    public record PromptFit(
            Prompt prompt,
            int estimatedInputTokens,
            int inputCeiling,
            int omittedMessageCount,
            boolean compacted
    ) {
    }
}
