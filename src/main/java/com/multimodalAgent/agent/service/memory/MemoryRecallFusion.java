package com.multimodalAgent.agent.service.memory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fusion algorithms for dense and BM25 long-term memory candidates. */
public final class MemoryRecallFusion {
    private static final double RRF_K = 60.0;

    private MemoryRecallFusion() {
    }

    public static List<Hit> fuse(List<MemoryVectorHit> vectorHits, List<MemoryKeywordHit> keywordHits,
            int limit, double bm25Weight, String method) {
        if (limit <= 0) return List.of();
        double keywordWeight = Math.max(0.0, Math.min(1.0, bm25Weight));
        double vectorWeight = 1.0 - keywordWeight;
        Map<Long, MutableHit> combined = new LinkedHashMap<>();
        String normalizedMethod = method == null ? "rrf" : method.trim().toLowerCase(Locale.ROOT);
        if ("weighted".equals(normalizedMethod)) {
            addWeightedVectors(combined, safeVectors(vectorHits), vectorWeight);
            addWeightedKeywords(combined, safeKeywords(keywordHits), keywordWeight);
        } else {
            addRankedVectors(combined, safeVectors(vectorHits), vectorWeight);
            addRankedKeywords(combined, safeKeywords(keywordHits), keywordWeight);
        }
        double maximum = combined.values().stream().mapToDouble(hit -> hit.score).max().orElse(0.0);
        if (maximum <= 0.0) return List.of();
        return combined.entrySet().stream()
                .sorted(Map.Entry.<Long, MutableHit>comparingByValue(
                        Comparator.comparingDouble(hit -> hit.score)).reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new Hit(entry.getKey(), entry.getValue().score / maximum,
                        List.copyOf(entry.getValue().sources)))
                .toList();
    }

    private static void addRankedVectors(Map<Long, MutableHit> combined,
            List<MemoryVectorHit> hits, double weight) {
        if (weight <= 0.0) return;
        for (int i = 0; i < hits.size(); i++) {
            MemoryVectorHit hit = hits.get(i);
            add(combined, hit.id(), weight / (RRF_K + i + 1), "vector");
        }
    }

    private static void addRankedKeywords(Map<Long, MutableHit> combined,
            List<MemoryKeywordHit> hits, double weight) {
        if (weight <= 0.0) return;
        for (int i = 0; i < hits.size(); i++) {
            MemoryKeywordHit hit = hits.get(i);
            add(combined, hit.factId(), weight / (RRF_K + i + 1), "bm25");
        }
    }

    private static void addWeightedVectors(Map<Long, MutableHit> combined,
            List<MemoryVectorHit> hits, double weight) {
        double maximum = hits.stream().mapToDouble(MemoryVectorHit::score).max().orElse(0.0);
        if (weight <= 0.0 || maximum <= 0.0) return;
        for (MemoryVectorHit hit : hits) {
            add(combined, hit.id(), weight * Math.max(0.0, hit.score()) / maximum, "vector");
        }
    }

    private static void addWeightedKeywords(Map<Long, MutableHit> combined,
            List<MemoryKeywordHit> hits, double weight) {
        double maximum = hits.stream().mapToDouble(MemoryKeywordHit::score).max().orElse(0.0);
        if (weight <= 0.0 || maximum <= 0.0) return;
        for (MemoryKeywordHit hit : hits) {
            add(combined, hit.factId(), weight * Math.max(0.0, hit.score()) / maximum, "bm25");
        }
    }

    private static void add(Map<Long, MutableHit> combined, Long factId,
            double score, String source) {
        if (factId == null || score <= 0.0) return;
        MutableHit hit = combined.computeIfAbsent(factId, ignored -> new MutableHit());
        hit.score += score;
        hit.sources.add(source);
    }

    private static List<MemoryVectorHit> safeVectors(List<MemoryVectorHit> hits) {
        if (hits == null) return List.of();
        return hits.stream().filter(hit -> hit != null && hit.id() != null).toList();
    }

    private static List<MemoryKeywordHit> safeKeywords(List<MemoryKeywordHit> hits) {
        if (hits == null) return List.of();
        return hits.stream().filter(hit -> hit != null && hit.factId() != null).toList();
    }

    public record Hit(Long factId, double score, List<String> sources) {
    }

    private static final class MutableHit {
        private double score;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
    }
}
