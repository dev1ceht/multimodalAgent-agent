package com.multimodalAgent.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
/**
 * 评测模式下的请求级内部追踪。
 *
 * <p>追踪默认关闭，只记录评测样本 ID、阶段结果和耗时，不保存原始用户输入。
 * 每个请求完成后追加到本地 JSONL，供外部评测运行器关联。</p>
 */
public class EvaluationTraceService {

    private final multimodalAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<Trace> current = new ThreadLocal<>();
    private final Object writeLock = new Object();

    public EvaluationTraceService(multimodalAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Trace start(String evaluationId, String model, String sanitizedInput) {
        if (!properties.getEvaluation().isEnabled()) {
            return null;
        }
        if (evaluationId == null || evaluationId.isBlank()) {
            throw new IllegalArgumentException("EVAL_MODE requires evaluationId on every chat request.");
        }
        return new Trace(
                evaluationId.trim(),
                model,
                sha256(sanitizedInput == null ? "" : sanitizedInput),
                Instant.now(),
                System.nanoTime());
    }

    public void bind(Trace trace) {
        if (trace != null) {
            current.set(trace);
        }
    }

    public void unbind() {
        current.remove();
    }

    public Optional<Trace> current() {
        return Optional.ofNullable(current.get());
    }

    public void put(String key, Object value) {
        current().ifPresent(trace -> trace.put(key, value));
    }

    public void append(String key, Object value) {
        current().ifPresent(trace -> trace.append(key, value));
    }

    public void duration(String key, long startedNanos) {
        current().ifPresent(trace -> trace.putDuration(key, startedNanos));
    }

    public void put(Trace trace, String key, Object value) {
        if (trace != null) {
            trace.put(key, value);
        }
    }

    public void duration(Trace trace, String key, long startedNanos) {
        if (trace != null) {
            trace.putDuration(key, startedNanos);
        }
    }

    public void finish(Trace trace, String status, String error) {
        if (trace == null || !trace.finished.compareAndSet(false, true)) {
            return;
        }
        trace.put("status", status);
        trace.put("finishedAt", Instant.now().toString());
        trace.put("totalMs", elapsedMillis(trace.startedNanos));
        if (error != null && !error.isBlank()) {
            trace.put("error", error);
        }
        Path output = Path.of(properties.getEvaluation().getOutputDir()).toAbsolutePath().normalize();
        Path file = output.resolve("traces.jsonl");
        try {
            Files.createDirectories(output);
            String line = objectMapper.writeValueAsString(trace.snapshot()) + System.lineSeparator();
            synchronized (writeLock) {
                Files.writeString(
                        file,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write evaluation trace to " + file, exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash evaluation input.", exception);
        }
    }

    private static double elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    public static final class Trace {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedNanos;

        private Trace(String evaluationId, String model, String inputSha256, Instant startedAt, long startedNanos) {
            this.startedNanos = startedNanos;
            values.put("evaluationId", evaluationId);
            values.put("model", model);
            values.put("inputSha256", inputSha256);
            values.put("startedAt", startedAt.toString());
        }

        public synchronized void put(String key, Object value) {
            values.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public synchronized void append(String key, Object value) {
            List<Object> list = (List<Object>) values.computeIfAbsent(key, ignored -> new ArrayList<>());
            list.add(value);
        }

        public synchronized void putDuration(String key, long startedNanos) {
            values.put(key, EvaluationTraceService.elapsedMillis(startedNanos));
        }

        public synchronized Map<String, Object> snapshot() {
            return new LinkedHashMap<>(values);
        }

        public double elapsedMillis() {
            return EvaluationTraceService.elapsedMillis(startedNanos);
        }
    }
}
