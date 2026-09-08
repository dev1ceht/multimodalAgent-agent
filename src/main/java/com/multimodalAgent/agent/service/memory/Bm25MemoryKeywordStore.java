package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.domain.MemoryFact;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    private final MemoryFactRepository facts;
    private final ConcurrentMap<Long, UserIndex> indexes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    public Bm25MemoryKeywordStore(MemoryFactRepository facts) {
        this.facts = facts;
    }

    @Override
    public void upsert(MemoryProjectionBatch batch) {
        if (batch == null || batch.userId() == null || batch.facts() == null || batch.facts().isEmpty()) {
            return;
        }
        Object lock = userLocks.computeIfAbsent(batch.userId(), ignored -> new Object());
        synchronized (lock) {
            UserIndex index = indexes.get(batch.userId());
            if (index == null) {
                // A later lazy load reads the just-committed canonical rows.
                return;
            }
            for (MemoryProjectionBatch.Fact fact : batch.facts()) {
                if (fact != null && fact.id() != null) {
                    index.replace(fact.id(), fact.content());
                }
            }
        }
    }

    @Override
    public List<MemoryKeywordHit> searchFacts(Long userId, String query, int limit) {
        if (userId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            UserIndex index = indexes.computeIfAbsent(userId,
                    id -> new UserIndex(facts.findByUserIdOrderByIdAsc(id)));
            return index.search(query, limit);
        }
    }

    private static final class UserIndex {
        private final Map<Long, Document> documents = new HashMap<>();
        private final Map<String, Map<Long, Integer>> postings = new HashMap<>();
        private long totalLength;

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
            for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
                postings.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).put(id, entry.getValue());
            }
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
