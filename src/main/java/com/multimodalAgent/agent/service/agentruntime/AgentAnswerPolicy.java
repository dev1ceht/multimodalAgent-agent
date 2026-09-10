package com.multimodalAgent.agent.service.agentruntime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Final-answer safety gate; model output is not trusted merely because it is textual. */
@Component
public final class AgentAnswerPolicy {

    private static final Pattern CITATION = Pattern.compile("\\bE([0-9]+)\\b");
    private static final Pattern INTERNAL_CONTENT = Pattern.compile(
            "(?i)(?:<tool_call>|</tool_call>|tool_call|function_call|assistant\\s+to=|"
                    + "recipient\\s*[:=]|user[_ ]?id\\s*[:=]|session[_ ]?id\\s*[:=]|"
                    + "staff\\s+notes?|internal\\s+risk|system\\s+prompt|chain\\s+of\\s+thought)");
    private static final int MAX_ANSWER_LENGTH = 12000;

    public AnswerDecision validate(AgentRunContext context, String answer) {
        if (answer == null || answer.isBlank()) {
            return reject("empty_final", "模型没有生成可公开的最终回答。");
        }
        String normalized = answer.trim();
        if (normalized.length() > MAX_ANSWER_LENGTH) {
            return reject("answer_too_long", "模型回答超过了公开输出上限。");
        }
        if (INTERNAL_CONTENT.matcher(normalized).find()) {
            return reject("internal_content", "模型回答包含不可公开的内部内容。");
        }
        Matcher matcher = CITATION.matcher(normalized);
        while (matcher.find()) {
            if (context == null || !context.hasEvidence("E" + matcher.group(1))) {
                return reject("invalid_citation", "模型引用了本轮不存在的证据。");
            }
        }
        return new AnswerDecision(true, normalized, "");
    }

    public String safeFallback(AgentRunContext context) {
        if (context != null && context.riskLevel() != null
                && context.riskLevel().name().equalsIgnoreCase("HIGH")) {
            return "我暂时无法完成完整的资料核对。请先联系身边可信任的人或学校支持人员；如果你正处于立即危险中，请联系当地紧急服务。";
        }
        return "我暂时无法可靠完成这次回答，请稍后重试。如果事情让你感到难以独自应对，也可以联系身边可信任的人或学校支持人员。";
    }

    private AnswerDecision reject(String code, String message) {
        return new AnswerDecision(false, message, code);
    }

    public record AnswerDecision(boolean accepted, String answer, String errorCode) {
        public AnswerDecision {
            answer = answer == null ? "" : answer.trim();
            errorCode = errorCode == null ? "" : errorCode.trim().toLowerCase(Locale.ROOT);
        }
    }
}
