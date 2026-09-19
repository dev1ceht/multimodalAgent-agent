package com.multimodalAgent.agent.service.context;

import com.multimodalAgent.agent.service.ai.AiMessage;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * Conservative, provider-neutral token estimate used before a request reaches a model.
 *
 * <p>This is intentionally labelled an estimate. A provider-specific tokenizer can replace the
 * implementation later without changing the context budget seam. CJK code points and symbols
 * are counted individually; contiguous ASCII text is counted in small word-like groups.</p>
 */
@Component
public class ContextTokenEstimator {

    public static final String VERSION = "script-aware-v1";

    public int estimate(AiMessage message) {
        if (message == null) {
            return 0;
        }
        return estimate(message.role()) + estimateText(message.content());
    }

    public int estimate(List<AiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream().mapToInt(this::estimate).sum();
    }

    public int estimate(Message message) {
        if (message == null) {
            return 0;
        }
        String role = message.getMessageType() == null
                ? "message"
                : message.getMessageType().getValue();
        return estimate(role) + estimateText(message.getText())
                + estimateMetadata(message);
    }

    public int estimateSpringMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream().mapToInt(this::estimate).sum();
    }

    private int estimate(String role) {
        return 3 + (role == null ? 0 : role.length() / 4);
    }

    private int estimateMetadata(Message message) {
        if (message.getMetadata() == null || message.getMetadata().isEmpty()) {
            return 0;
        }
        return Math.max(1, estimateText(message.getMetadata().toString()) / 2);
    }

    private int estimateText(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isAsciiWord(codePoint)) {
                asciiRun++;
                continue;
            }
            tokens += groupedAsciiTokens(asciiRun);
            asciiRun = 0;
            tokens++;
        }
        return Math.max(1, tokens + groupedAsciiTokens(asciiRun));
    }

    private int groupedAsciiTokens(int characters) {
        return characters <= 0 ? 0 : (characters + 3) / 4;
    }

    private boolean isAsciiWord(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint)
                || codePoint == '_' || codePoint == '-');
    }
}
