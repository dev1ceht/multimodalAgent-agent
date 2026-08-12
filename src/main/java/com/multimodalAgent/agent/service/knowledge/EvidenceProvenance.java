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
        int sourceIndex,
        String parentKey,
        int childIndex,
        String sectionPath,
        int startOffset,
        int endOffset,
        int pageStart,
        int pageEnd
) {

    public EvidenceProvenance(String versionKey, String vectorId, int sourceIndex) {
        this(versionKey, vectorId, sourceIndex, "", -1, "", -1, -1, -1, -1);
    }

    public EvidenceProvenance {
        knowledgeVersionKey = normalize(knowledgeVersionKey);
        vectorId = normalize(vectorId);
        parentKey = normalize(parentKey);
        sectionPath = normalize(sectionPath);
        sourceIndex = sourceIndex < 0 ? -1 : sourceIndex;
        childIndex = childIndex < 0 ? -1 : childIndex;
        startOffset = startOffset < 0 ? -1 : startOffset;
        endOffset = endOffset < 0 ? -1 : endOffset;
        pageStart = pageStart < 0 ? -1 : pageStart;
        pageEnd = pageEnd < 0 ? -1 : pageEnd;
    }

    public static EvidenceProvenance unknown() {
        return new EvidenceProvenance("", "", -1);
    }

    public EvidenceProvenance withKnowledgeVersionKey(String versionKey) {
        return new EvidenceProvenance(
                versionKey, vectorId, sourceIndex, parentKey, childIndex,
                sectionPath, startOffset, endOffset, pageStart, pageEnd);
    }

    public EvidenceProvenance withParent(
            String newParentKey,
            int newChildIndex,
            String newSectionPath,
            Integer newStartOffset,
            Integer newEndOffset,
            Integer newPageStart,
            Integer newPageEnd
    ) {
        return new EvidenceProvenance(
                knowledgeVersionKey,
                vectorId,
                sourceIndex,
                newParentKey,
                newChildIndex,
                newSectionPath,
                valueOrUnknown(newStartOffset),
                valueOrUnknown(newEndOffset),
                valueOrUnknown(newPageStart),
                valueOrUnknown(newPageEnd));
    }

    private static int valueOrUnknown(Integer value) {
        return value == null ? -1 : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
