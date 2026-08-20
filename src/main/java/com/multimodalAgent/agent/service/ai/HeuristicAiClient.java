package com.multimodalAgent.agent.service.ai;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import reactor.core.publisher.Flux;

/**
 * 本地 mock 模型客户端。
 *
 * <p>用于无模型环境下演示完整业务流程，支持意图分类、心理评估和简单回答。</p>
 */
public class HeuristicAiClient implements AiClient {

    @Override
    public String complete(List<AiMessage> messages) {
        String prompt = messages.stream()
                .map(AiMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        String input = lastUserMessage(messages);
        if (prompt.contains("请求路由器")) {
            return route(input);
        }
        if (prompt.contains("query rewriting module")) {
            String query = currentInput(input).trim();
            if (query.isBlank()) {
                query = "校园心理支持";
            }
            return "{\"query\":\"" + escapeJson(query) + "\"}";
        }
        if (prompt.contains("Agentic RAG evidence reviewer")) {
            return "{\"sufficient\":true,\"reason\":\"候选证据可以支持安全、通用的心理支持回答\"}";
        }
        if (prompt.contains("\"emotion\"") || prompt.contains("心理健康消息")) {
            return analyze(input);
        }
        return answer(input, prompt);
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        String answer = complete(messages);
        return Flux.fromArray(answer.split("(?<=.)"))
                .delayElements(Duration.ofMillis(12));
    }

    private String route(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        String current = currentInput(input).toLowerCase(Locale.ROOT);
        if (RiskLexicon.hasExplicitHighRiskSignal(current)) {
            return "{\"needsRag\":true,\"riskLevel\":\"HIGH\",\"confidence\":0.98,\"reason\":\"命中明确高风险信号\"}";
        }
        if (RiskLexicon.hasConsultSignal(current) || RiskLexicon.hasConsultSignal(normalized)) {
            return "{\"needsRag\":true,\"riskLevel\":\"LOW\",\"confidence\":0.82,\"reason\":\"检测到心理困扰或支持需求\"}";
        }
        if (containsAny(current, "心理", "咨询", "危机", "辅导员", "心理中心", "保密", "120", "110", "12356")) {
            return "{\"needsRag\":true,\"riskLevel\":\"NONE\",\"confidence\":0.85,\"reason\":\"心理支持知识问答\"}";
        }
        return "{\"needsRag\":false,\"riskLevel\":\"NONE\",\"confidence\":0.80,\"reason\":\"普通聊天或通用任务\"}";
    }

    private String analyze(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        String current = currentInput(input).toLowerCase(Locale.ROOT);
        if (RiskLexicon.hasHighRiskSignal(current)) {
            return "{\"observedStates\":[\"HOPELESS\"],\"functionalImpact\":\"SIGNIFICANT\",\"safetyRisk\":\"HIGH\",\"evidence\":[\"检测到明确的当前安全信号\"],\"confidence\":0.92,\"summary\":\"检测到明确的高风险自伤或危险信号\"}";
        }
        if (containsAny(current, "抑郁", "低落", "压抑", "崩溃", "难过", "绝望", "depress", "hopeless")) {
            return "{\"observedStates\":[\"LOW_MOOD\"],\"functionalImpact\":\"SIGNIFICANT\",\"safetyRisk\":\"MEDIUM\",\"evidence\":[\"检测到持续低落相关表达\"],\"confidence\":0.82,\"summary\":\"检测到持续低落相关表达\"}";
        }
        if (containsAny(current, "焦虑", "压力", "睡不着", "失眠", "紧张", "anxious", "stress", "insomnia")) {
            return "{\"observedStates\":[\"ANXIOUS\",\"SLEEP_DIFFICULTY\"],\"functionalImpact\":\"MILD\",\"safetyRisk\":\"LOW\",\"evidence\":[\"检测到焦虑、压力或睡眠困扰相关表达\"],\"confidence\":0.78,\"summary\":\"检测到焦虑、压力或睡眠困扰相关表达\"}";
        }
        if (RiskLexicon.hasConsultSignal(normalized)) {
            return "{\"observedStates\":[\"ANXIOUS\"],\"functionalImpact\":\"MILD\",\"safetyRisk\":\"LOW\",\"evidence\":[\"结合上下文检测到心理支持延续表达\"],\"confidence\":0.70,\"summary\":\"结合上下文检测到心理支持延续表达\"}";
        }
        return "{\"observedStates\":[\"NEUTRAL\"],\"functionalImpact\":\"NONE\",\"safetyRisk\":\"NONE\",\"evidence\":[],\"confidence\":0.70,\"summary\":\"未检测到现实心理困扰或风险信号\"}";
    }

    private String answer(String input, String prompt) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (RiskLexicon.hasExplicitHighRiskSignal(normalized) || prompt.contains("高风险处理规则")) {
            return """
                    我会认真对待你刚才说的这些话，你不需要独自扛住这一刻。你现在是否正处于马上会伤害自己或他人的危险中？

                    请先离开任何可能造成伤害的物品或环境，移动到有其他人在场的安全位置。马上联系一个现实中能到你身边的人，比如同学、室友、家人、辅导员或学校心理中心；如果危险迫近，请立即联系当地紧急服务。

                    请只回复我这一点：现在有没有一个人能立刻到你身边陪你？
                    """;
        }
        if (RiskLexicon.hasConsultSignal(normalized) || prompt.contains("检索知识：")) {
            return consultAnswer(input);
        }
        return """
                我在。你可以把我当作一个校园心理支持助手来用：日常闲聊我会自然回应；如果你聊到压力、焦虑、睡眠、人际关系或学习困扰，我会先判断意图，再结合最近上下文和知识库给你更具体的建议。

                你现在想聊轻松一点的内容，还是想说说最近真正让你卡住的一件事？
                """;
    }

    private String consultAnswer(String input) {
        String focus = focusFrom(input);
        return """
                我能感觉到这件事已经占用了你不少精力。先不用急着把它归结成“我是不是不行”，我们可以先把它拆小一点看：%s。

                你现在可以先做几件很具体的小事：
                1. 用一两句话写下最困扰你的触发点，尽量区分“发生了什么”和“我脑子里正在担心什么”。
                2. 给身体一个短暂停顿：慢慢呼气 6 秒、吸气 4 秒，重复 3 轮，先把紧绷感降一点。
                3. 今天只选一个能完成的小动作，比如给老师/同学发一条确认信息、洗个热水澡、或提前 20 分钟放下手机。
                4. 如果这种状态已经持续两周以上，或明显影响上课、睡眠、饮食，建议尽快联系学校心理中心或辅导员。

                我们可以继续从最具体的地方开始。这个困扰最明显是在什么时候出现的？
                """.formatted(focus);
    }

    private String focusFrom(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "睡不着", "失眠", "睡眠", "insomnia")) {
            return "你提到的睡眠问题可能正在放大白天的疲惫和焦虑";
        }
        if (containsAny(normalized, "考试", "考研", "学习", "挂科", "作业", "论文")) {
            return "你面对的学习或考试压力需要被拆成可处理的任务，而不是一次性压在心里";
        }
        if (containsAny(normalized, "分手", "恋爱", "亲密关系", "关系", "室友", "朋友", "社交")) {
            return "关系里的不确定和消耗很容易让人反复想、反复内耗";
        }
        if (containsAny(normalized, "低落", "抑郁", "难过", "没动力", "想哭", "压抑")) {
            return "你现在的低落感值得被认真看见，而不是被简单劝成“想开点”";
        }
        if (containsAny(normalized, "焦虑", "紧张", "害怕", "恐惧", "压力", "烦躁")) {
            return "你身体和脑子都像是在持续警觉，所以会很累";
        }
        return "先抓住一个最让你难受的场景，比泛泛地处理全部情绪更容易开始";
    }

    private String lastUserMessage(List<AiMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            AiMessage message = messages.get(index);
            if ("user".equals(message.role())) {
                return message.content();
            }
        }
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
    }

    private String currentInput(String input) {
        String marker = "当前输入：";
        int index = input.lastIndexOf(marker);
        if (index < 0) {
            return input;
        }
        return input.substring(index + marker.length()).trim();
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
