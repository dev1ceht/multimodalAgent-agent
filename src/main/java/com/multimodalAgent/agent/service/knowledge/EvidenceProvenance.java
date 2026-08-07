package com.multimodalAgent.agent.service.knowledge;

/**
 * Immutable identity of the knowledge slice behind a retrieved result.
 *
 * <p>An unknown field is represented by an empty string or {@code -1}; callers can therefore
 * record provenance without null-sensitive maps or log formatting.</p>
 */
public record EvidenceProvenance(
        String knowledgeVersionKey,
        String vectorId,
        int sourceIndex
) {

    public EvidenceProvenance {
        knowledgeVersionKey = normalize(knowledgeVersionKey);
        vectorId = normalize(vectorId);
        sourceIndex = sourceIndex < 0 ? -1 : sourceIndex;
    }

    public static EvidenceProvenance unknown() {
        return new EvidenceProvenance("", "", -1);
    }

    public EvidenceProvenance withKnowledgeVersionKey(String versionKey) {
        return new EvidenceProvenance(versionKey, vectorId, sourceIndex);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
