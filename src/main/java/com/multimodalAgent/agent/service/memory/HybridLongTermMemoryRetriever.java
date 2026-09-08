package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.domain.MemoryTopic;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import com.multimodalAgent.agent.repository.MemoryFactTopicRepository;
import com.multimodalAgent.agent.repository.MemoryTopicRepository;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 向量/BM25 种子 + 主题聚合 + Neo4j 多跳 + 同会话时序邻居的融合召回。 */
@Service
public class HybridLongTermMemoryRetriever implements LongTermMemoryRetriever {
    private final MemoryVectorStore vectors;
    private final MemoryKeywordStore keywords;
    private final MemoryGraphStore graph;
    private final EmbeddingClient embeddings;
    private final MemoryFactRepository facts;
    private final MemoryTopicRepository topics;
    private final MemoryFactTopicRepository memberships;
    private final multimodalAgentProperties properties;

    public HybridLongTermMemoryRetriever(MemoryVectorStore vectors, MemoryKeywordStore keywords,
            MemoryGraphStore graph,
            EmbeddingClient embeddings, MemoryFactRepository facts, MemoryTopicRepository topics,
            MemoryFactTopicRepository memberships, multimodalAgentProperties properties) {
        this.vectors = vectors;
        this.keywords = keywords;
        this.graph = graph;
        this.embeddings = embeddings;
        this.facts = facts;
        this.topics = topics;
        this.memberships = memberships;
        this.properties = properties;
    }

    @Override
    public LongTermMemoryRecall recall(LongTermMemoryQuery query) {
        if (!properties.getMemory().isEnabled()) {
            return LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.DISABLED, "长期记忆已禁用");
        }
        if (query.userId() == null || query.text() == null || query.text().isBlank()) {
            return LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.EMPTY, "缺少检索输入");
        }
        try {
            int limit = Math.max(1, properties.getMemory().getTopK());
            boolean bm25Enabled = properties.getMemory().isBm25Enabled();
            int candidateLimit = bm25Enabled ? expandedCandidateLimit(limit) : limit;
            List<Double> vector = embeddings.embed(query.text());
            List<MemoryVectorHit> factHits = vectors.searchFacts(query.userId(), vector, candidateLimit);
            List<MemoryRecallFusion.Hit> fusedFactHits = bm25Enabled
                    ? MemoryRecallFusion.fuse(factHits,
                            keywords.searchFacts(query.userId(), query.text(), candidateLimit),
                            candidateLimit, properties.getMemory().getBm25Weight(),
                            properties.getMemory().getBm25FusionMethod())
                    : factHits.stream().map(hit -> new MemoryRecallFusion.Hit(
                            hit.id(), hit.score(), List.of("vector"))).toList();
            List<MemoryVectorHit> topicCandidates = vectors.searchTopics(
                    query.userId(), vector, Math.max(4, Math.min(32, limit * 4)));
            List<Long> candidateTopicIds = topicCandidates.stream().map(MemoryVectorHit::id).distinct().toList();
            Map<Long, MemoryTopic> currentTopics =
                    topics.findAllById(candidateTopicIds).stream()
                            .filter(t -> Objects.equals(t.getUserId(), query.userId()))
                            .collect(Collectors.toMap(MemoryTopic::getId, Function.identity()));
            List<MemoryVectorHit> topicHits = topicCandidates.stream()
                    .filter(hit -> hit.projectionRevision() == null
                            || (currentTopics.containsKey(hit.id())
                            && hit.projectionRevision() == currentTopics.get(hit.id()).getProjectionRevision()))
                    .limit(Math.min(4, limit)).toList();
            Map<Long, Score> scores = new LinkedHashMap<>();
            for (MemoryRecallFusion.Hit hit : fusedFactHits) {
                scores.computeIfAbsent(hit.factId(), ignored -> new Score())
                        .add(0.55 * hit.score(), String.join("+", hit.sources()));
            }
            List<Long> topicIds = topicHits.stream().map(MemoryVectorHit::id).distinct().toList();
            for (var membership : memberships.findByTopicIdIn(topicIds)) {
                scores.computeIfAbsent(membership.getFactId(), ignored -> new Score()).add(0.20, "topic");
            }
            List<Long> seeds = fusedFactHits.stream()
                    .map(MemoryRecallFusion.Hit::factId).limit(6).toList();
            Map<Long, LinkedHashSet<String>> graphPaths = new LinkedHashMap<>();
            for (MemoryGraphHit hit : graph.expand(query.userId(), seeds, properties.getMemory().getGraphHops())) {
                scores.computeIfAbsent(hit.factId(), ignored -> new Score())
                        .add(0.20 / Math.max(1, hit.depth()), "graph:" + hit.relationType().name());
                if (hit.pathContext() != null && !hit.pathContext().isBlank()) {
                    graphPaths.computeIfAbsent(hit.factId(), ignored -> new LinkedHashSet<>())
                            .add(hit.pathContext());
                }
            }
            addTemporalContext(query, seeds, scores);

            Map<Long, MemoryFact> byId = facts.findAllById(scores.keySet()).stream()
                    .filter(f -> Objects.equals(f.getUserId(), query.userId()))
                    .collect(Collectors.toMap(MemoryFact::getId, Function.identity()));
            List<LongTermMemoryRecall.Item> items = scores.entrySet().stream()
                    .filter(entry -> byId.containsKey(entry.getKey()))
                    .sorted(Map.Entry.<Long, Score>comparingByValue(
                            Comparator.comparingDouble(Score::value)).reversed())
                    .limit(limit)
                    .map(entry -> new LongTermMemoryRecall.Item(entry.getKey(),
                            byId.get(entry.getKey()).getContent(), Math.min(1.0, entry.getValue().value()),
                            String.join("+", entry.getValue().sources)))
                    .toList();
            if (items.isEmpty()) return LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.EMPTY, "无相关长期记忆");
            String topicContext = topicIds.stream().map(currentTopics::get).filter(Objects::nonNull)
                    .map(t -> "主题：" + t.getTitle() + " — " + t.getSummary())
                    .collect(Collectors.joining("\n"));
            String factContext = items.stream().map(i -> {
                MemoryFact fact = byId.get(i.factId());
                return "事实[" + fact.getOccurredAt() + "]：" + i.content();
            }).collect(Collectors.joining("\n"));
            Set<Long> recalledIds = items.stream().map(LongTermMemoryRecall.Item::factId)
                    .collect(Collectors.toSet());
            String graphContext = graphPaths.entrySet().stream()
                    .filter(entry -> recalledIds.contains(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .map(path -> "关系链：" + path)
                    .collect(Collectors.joining("\n"));
            return new LongTermMemoryRecall(LongTermMemoryRecall.Status.READY, items,
                    String.join("\n", List.of(topicContext, factContext, graphContext)).trim(),
                    bm25Enabled
                            ? "vector+bm25+topic+graph+temporal"
                            : "vector+topic+graph+temporal");
        } catch (RuntimeException exception) {
            return LongTermMemoryRecall.empty(LongTermMemoryRecall.Status.DEGRADED,
                    "长期记忆投影暂不可用: " + exception.getClass().getSimpleName());
        }
    }

    private int expandedCandidateLimit(int limit) {
        long expanded = (long) limit
                * Math.max(1, properties.getMemory().getBm25CandidateMultiplier());
        return (int) Math.max(limit, Math.min(256L, expanded));
    }

    private void addTemporalContext(LongTermMemoryQuery query, List<Long> seeds, Map<Long, Score> scores) {
        int window = Math.max(0, properties.getMemory().getTemporalWindow());
        if (window == 0 || seeds.isEmpty()) return;
        Map<Long, MemoryFact> seedFacts = facts.findAllById(seeds).stream()
                .filter(f -> Objects.equals(f.getUserId(), query.userId()))
                .collect(Collectors.toMap(MemoryFact::getId, Function.identity()));
        for (MemoryFact seed : seedFacts.values()) {
            List<MemoryFact> timeline = facts.findByUserIdAndSessionIdOrderByOccurredAtAsc(
                    query.userId(), seed.getSessionId());
            int index = java.util.stream.IntStream.range(0, timeline.size())
                    .filter(i -> Objects.equals(timeline.get(i).getId(), seed.getId())).findFirst().orElse(-1);
            if (index < 0) continue;
            for (int i = Math.max(0, index - window); i <= Math.min(timeline.size() - 1, index + window); i++) {
                if (i != index) scores.computeIfAbsent(timeline.get(i).getId(), ignored -> new Score())
                        .add(0.10 / Math.abs(i - index), "temporal");
            }
        }
    }

    private static final class Score {
        private double value;
        private final List<String> sources = new ArrayList<>();
        void add(double increment, String source) { value += increment; sources.add(source); }
        double value() { return value; }
    }
}
