package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Rebuildable, per-user BM25 index for long-term Facts.
 *
 * <p>The canonical store remains MySQL. The index is intentionally kept in
 * memory so a restart only costs a lazy rebuild for users that are queried.
 * Han runs use unigrams and bigrams; latin/digit runs use one normalized token.
 */
@Service
public class Bm25MemoryKeywordStore implements MemoryKeywordStore {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int LOCK_STRIPES = 64;

    private final MemoryFactRepository facts;
    private final multimodalAgentProperties properties;
    private final Map<Long, CachedIndex> indexes = new LinkedHashMap<>(16, 0.75f, true);
    private final Object cacheLock = new Object();
    private final Object[] userLocks = new Object[LOCK_STRIPES];

    public Bm25MemoryKeywordStore(MemoryFactRepository facts, multimodalAgentProperties properties) {
        this.facts = facts;
        this.properties = properties;
        for (int i = 0; i < userLocks.length; i++) userLocks[i] = new Object();
    }

    @Override
    public void upsert(MemoryProjectionBatch batch) {
        if (batch == null || batch.userId() == null || batch.facts() == null || batch.facts().isEmpty()) {
            return;
        }
        Object lock = lockFor(batch.userId());
        synchronized (lock) {
            CachedIndex cached = getCached(batch.userId());
            if (cached == null) {
                // A later lazy load reads the just-committed canonical rows.
                return;
            }
            for (MemoryProjectionBatch.Fact fact : batch.facts()) {
                if (fact != null && fact.id() != null) {
                    cached.index.replace(fact.id(), fact.content());
                }
            }
            cached.documentCount = cached.index.documentCount();
            enforceCacheBounds();
        }
    }

    @Override
    public List<MemoryKeywordHit> searchFacts(Long userId, String query, int limit) {
        if (userId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Object lock = lockFor(userId);
        synchronized (lock) {
            CachedIndex cached = getCached(userId);
            if (cached == null) {
                cached = load(userId);
                putCached(userId, cached);
            } else {
                refreshIfDue(userId, cached);
            }
            return cached.index.search(query, limit);
        }
    }

    private CachedIndex load(Long userId) {
        UserIndex index = new UserIndex(facts.findByUserIdOrderByIdAsc(userId));
        return new CachedIndex(index, System.nanoTime());
    }

    private void refreshIfDue(Long userId, CachedIndex cached) {
        long interval = TimeUnit.SECONDS.toNanos(
                Math.max(0, properties.getMemory().getBm25RefreshIntervalSeconds()));
        long now = System.nanoTime();
        if (interval > 0 && now - cached.lastRefreshNanos < interval) return;
        for (MemoryFact fact : facts.findByUserIdAndIdGreaterThanOrderByIdAsc(
                userId, cached.index.maxFactId())) {
            cached.index.replace(fact.getId(), fact.getContent());
        }
        cached.lastRefreshNanos = now;
        cached.documentCount = cached.index.documentCount();
        enforceCacheBounds();
    }

    private Object lockFor(Long userId) {
        return userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
    }

    private CachedIndex getCached(Long userId) {
        synchronized (cacheLock) {
            return indexes.get(userId);
        }
    }

    private void putCached(Long userId, CachedIndex cached) {
        synchronized (cacheLock) {
            indexes.put(userId, cached);
            enforceCacheBoundsLocked();
        }
    }

    private void enforceCacheBounds() {
        synchronized (cacheLock) {
            enforceCacheBoundsLocked();
        }
    }

    private void enforceCacheBoundsLocked() {
        int maxUsers = Math.max(1, properties.getMemory().getBm25MaxCachedUsers());
        int maxFacts = Math.max(1, properties.getMemory().getBm25MaxCachedFacts());
        while (!indexes.isEmpty()
                && (indexes.size() > maxUsers || cachedFactCount() > maxFacts)) {
            var eldest = indexes.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    private long cachedFactCount() {
        return indexes.values().stream().mapToLong(cached -> cached.documentCount).sum();
    }

    private static final class CachedIndex {
        private final UserIndex index;
        private long lastRefreshNanos;
        private volatile int documentCount;

        CachedIndex(UserIndex index, long lastRefreshNanos) {
            this.index = index;
            this.lastRefreshNanos = lastRefreshNanos;
            this.documentCount = index.documentCount();
        }
    }

    private static final class UserIndex {
        private final Map<Long, Document> documents = new HashMap<>();
        private final Map<String, Map<Long, Integer>> postings = new HashMap<>();
        private long totalLength;
        private long maxFactId;

        UserIndex(List<MemoryFact> facts) {
            if (facts != null) {
                for (MemoryFact fact : facts) {
                    if (fact != null && fact.getId() != null) {
                        replace(fact.getId(), fact.getContent());
                    }
                }
            }
        }

        void replace(Long id, String content) {
            Document previous = documents.remove(id);
            if (previous != null) {
                totalLength -= previous.length();
                for (String term : previous.termFrequency().keySet()) {
                    Map<Long, Integer> posting = postings.get(term);
                    if (posting != null) {
                        posting.remove(id);
                        if (posting.isEmpty()) postings.remove(term);
                    }
                }
            }
            Map<String, Integer> termFrequency = termFrequency(content);
            int length = termFrequency.values().stream().mapToInt(Integer::intValue).sum();
            Document document = new Document(id, termFrequency, length);
            documents.put(id, document);
            totalLength += length;
            maxFactId = Math.max(maxFactId, id);
            for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
                postings.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).put(id, entry.getValue());
            }
        }

        long maxFactId() {
            return maxFactId;
        }

        int documentCount() {
            return documents.size();
        }

        List<MemoryKeywordHit> search(String query, int limit) {
            List<String> terms = tokenize(query).stream().distinct().toList();
            if (terms.isEmpty() || documents.isEmpty()) return List.of();
            double averageLength = Math.max(1.0, totalLength / (double) documents.size());
            Map<Long, Double> scores = new HashMap<>();
            int documentCount = documents.size();
            for (String term : terms) {
                Map<Long, Integer> posting = postings.get(term);
                if (posting == null || posting.isEmpty()) continue;
                double idf = Math.log(1.0 + (documentCount - posting.size() + 0.5)
                        / (posting.size() + 0.5));
                for (Map.Entry<Long, Integer> entry : posting.entrySet()) {
                    Document document = documents.get(entry.getKey());
                    double termFrequency = entry.getValue();
                    double denominator = termFrequency + K1 * (1 - B + B * document.length() / averageLength);
                    double contribution = idf * (termFrequency * (K1 + 1)) / denominator;
                    scores.merge(entry.getKey(), contribution, Double::sum);
                }
            }
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .map(entry -> new MemoryKeywordHit(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }

    private record Document(Long id, Map<String, Integer> termFrequency, int length) {
    }

    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokenize(text)) {
            frequencies.merge(token, 1, Integer::sum);
        }
        return frequencies;
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushLatin(latin, tokens);
                han.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHan(han, tokens);
                latin.appendCodePoint(Character.toLowerCase(codePoint));
            } else {
                flushLatin(latin, tokens);
                flushHan(han, tokens);
            }
            offset += Character.charCount(codePoint);
        }
        flushLatin(latin, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder value, List<String> tokens) {
        if (value.length() == 0) return;
        tokens.add(value.toString().toLowerCase(Locale.ROOT));
        value.setLength(0);
    }

    private static void flushHan(StringBuilder value, List<String> tokens) {
        if (value.length() == 0) return;
        int[] codePoints = value.toString().codePoints().toArray();
        for (int codePoint : codePoints) {
            tokens.add(new String(Character.toChars(codePoint)));
        }
        for (int i = 0; i + 1 < codePoints.length; i++) {
            tokens.add(new String(Character.toChars(codePoints[i]))
                    + new String(Character.toChars(codePoints[i + 1])));
        }
        value.setLength(0);
    }
}
