package com.multimodalAgent.agent.service.agentruntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.MindCareAgentProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Central, run-scoped budget gate for model calls, tool calls and argument repairs.
 *
 * <p>The gate is deliberately independent from ReactAgent hooks. A hook may be invoked more
 * than once by a framework implementation, but the counters below belong to one run and are
 * never reset by a hook.</p>
 */
@Component
public final class AgentBudgetPolicy {

    private static final ObjectMapper ARGUMENT_MAPPER = new ObjectMapper();

    private final MindCareAgentProperties properties;

    public AgentBudgetPolicy(MindCareAgentProperties properties) {
        this.properties = properties;
    }

    public Budget start(Instant deadline) {
        Instant effectiveDeadline = deadline == null ? Instant.now() : deadline;
        return new Budget(
                effectiveDeadline,
                nonNegative(properties.getMaxModelCalls()),
                nonNegative(properties.getMaxToolCalls()),
                Math.max(1, properties.getMaxIdenticalToolCalls()),
                nonNegative(properties.getMaxArgumentRepairs()),
                nonNegative(properties.getMaxTotalToolResultChars()));
    }

    public BudgetDecision beforeModelCall(Budget budget) {
        if (budget == null) {
            return BudgetDecision.deny("missing_budget", "当前请求无法建立安全执行预算。");
        }
        synchronized (budget) {
            BudgetDecision stopped = stopReason(budget);
            if (!stopped.allowed()) {
                return stopped;
            }
            if (budget.modelCalls.get() >= budget.maxModelCalls) {
                return BudgetDecision.deny("model_call_budget_exceeded", "已达到本次对话的模型调用上限。");
            }
            budget.modelCalls.incrementAndGet();
            return BudgetDecision.allow();
        }
    }

    public BudgetDecision beforeToolCall(Budget budget, String toolName, String normalizedArguments) {
        if (budget == null) {
            return BudgetDecision.deny("missing_budget", "当前请求无法建立安全执行预算。");
        }
        String safeName = toolName == null ? "" : toolName.trim();
        String fingerprint = fingerprint(safeName + "\n" + normalizeArguments(normalizedArguments));
        synchronized (budget) {
            BudgetDecision stopped = stopReason(budget);
            if (!stopped.allowed()) {
                return stopped;
            }
            if (budget.toolCalls.get() >= budget.maxToolCalls) {
                return BudgetDecision.deny("tool_call_budget_exceeded", "已达到本次对话的工具调用上限。");
            }
            int repeated = budget.identicalCalls.merge(fingerprint, 1, Integer::sum);
            if (repeated > budget.maxIdenticalToolCalls) {
                return BudgetDecision.deny(
                        "identical_tool_call_budget_exceeded",
                        "检测到重复工具调用，已停止继续尝试。");
            }
            budget.toolCalls.incrementAndGet();
            return BudgetDecision.allow();
        }
    }

    public BudgetDecision recordArgumentRepair(Budget budget) {
        if (budget == null) {
            return BudgetDecision.deny("missing_budget", "当前请求无法建立安全执行预算。");
        }
        synchronized (budget) {
            BudgetDecision stopped = stopReason(budget);
            if (!stopped.allowed()) {
                return stopped;
            }
            if (budget.argumentRepairs.get() >= budget.maxArgumentRepairs) {
                return BudgetDecision.deny("argument_repair_budget_exceeded", "工具参数无法在有限次数内修正。");
            }
            budget.argumentRepairs.incrementAndGet();
            return BudgetDecision.allow();
        }
    }

    public boolean recordToolResult(Budget budget, int resultCharacters) {
        if (budget == null || resultCharacters < 0) {
            return false;
        }
        synchronized (budget) {
            long next = (long) budget.totalToolResultChars.get() + resultCharacters;
            if (next > budget.maxTotalToolResultChars) {
                return false;
            }
            budget.totalToolResultChars.set((int) next);
            return true;
        }
    }

    public void cancel(Budget budget) {
        if (budget != null) {
            budget.cancelled.set(true);
        }
    }

    public BudgetSnapshot snapshot(Budget budget) {
        if (budget == null) {
            return new BudgetSnapshot(0, 0, 0, 0, 0);
        }
        return new BudgetSnapshot(
                budget.modelCalls.get(),
                budget.toolCalls.get(),
                budget.argumentRepairs.get(),
                budget.totalToolResultChars.get(),
                budget.identicalCalls.size());
    }

    private BudgetDecision stopReason(Budget budget) {
        if (budget.cancelled.get()) {
            return BudgetDecision.deny("cancelled", "本次请求已取消。");
        }
        if (!Instant.now().isBefore(budget.deadline)) {
            return BudgetDecision.deny("deadline_exceeded", "本次请求已超时。");
        }
        return BudgetDecision.allow();
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static String normalizeArguments(String arguments) {
        String raw = arguments == null || arguments.isBlank() ? "{}" : arguments.trim();
        try {
            JsonNode parsed = ARGUMENT_MAPPER.readTree(raw);
            return parsed == null ? raw : parsed.toString();
        } catch (JsonProcessingException exception) {
            return raw.replaceAll("\\s+", " ");
        }
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime.", exception);
        }
    }

    public record BudgetDecision(boolean allowed, String errorCode, String publicMessage) {
        public static BudgetDecision allow() {
            return new BudgetDecision(true, "", "");
        }

        public static BudgetDecision deny(String errorCode, String publicMessage) {
            return new BudgetDecision(false, errorCode, publicMessage);
        }
    }

    public record BudgetSnapshot(
            int modelCalls,
            int toolCalls,
            int argumentRepairs,
            int totalToolResultChars,
            int uniqueToolFingerprints
    ) {
    }

    public static final class Budget {
        private final Instant deadline;
        private final int maxModelCalls;
        private final int maxToolCalls;
        private final int maxIdenticalToolCalls;
        private final int maxArgumentRepairs;
        private final int maxTotalToolResultChars;
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger argumentRepairs = new AtomicInteger();
        private final AtomicInteger totalToolResultChars = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Map<String, Integer> identicalCalls = new ConcurrentHashMap<>();

        private Budget(
                Instant deadline,
                int maxModelCalls,
                int maxToolCalls,
                int maxIdenticalToolCalls,
                int maxArgumentRepairs,
                int maxTotalToolResultChars
        ) {
            this.deadline = deadline;
            this.maxModelCalls = maxModelCalls;
            this.maxToolCalls = maxToolCalls;
            this.maxIdenticalToolCalls = maxIdenticalToolCalls;
            this.maxArgumentRepairs = maxArgumentRepairs;
            this.maxTotalToolResultChars = maxTotalToolResultChars;
        }

        public Instant deadline() {
            return deadline;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            cancelled.set(true);
        }
    }
}
