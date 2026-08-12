package com.multimodalAgent.agent.service.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersion;
import com.multimodalAgent.agent.domain.KnowledgeVersionChunk;
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionSectionRepository;
import com.multimodalAgent.agent.domain.KnowledgeVersionSection;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.ElasticsearchGateway;
import com.multimodalAgent.agent.service.knowledge.ElasticsearchHybridQuery;
import com.multimodalAgent.agent.service.knowledge.EvidenceProvenance;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrieverTests {

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private KnowledgeVersionRepository knowledgeVersionRepository;

    @Mock
    private KnowledgeVersionChunkRepository knowledgeVersionChunkRepository;

    @Mock
    private KnowledgeVersionSectionRepository knowledgeVersionSectionRepository;

    @Mock
    private ElasticsearchGateway elasticsearchGateway;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EvaluationTraceService evaluationTraceService;

    @Mock
    private EvidenceReranker evidenceReranker;

    @Mock
    private OperationalMetrics operationalMetrics;

    private multimodalAgentProperties properties;
    private KnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getKnowledge().setUseElasticsearch(true);
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setTopK(4);
        retriever = new KnowledgeRetriever(
                knowledgeChunkRepository,
                knowledgeVersionRepository,
                knowledgeVersionChunkRepository,
                knowledgeVersionSectionRepository,
                properties,
                elasticsearchGateway,
                embeddingClient,
                new ObjectMapper(),
                evaluationTraceService,
                evidenceReranker,
                operationalMetrics);
    }

    @Test
    void deduplicatesChildHitsAndReturnsTheirParentWithinTheEvidenceBudget() {
        properties.getKnowledge().setEvidenceCharacterBudget(80);
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("mindcare-knowledge-v1");
        activeVersion.setEmbeddingModel("test-embedding");
        activeVersion.setEmbeddingDimensions(2);
        activeVersion.setChunkingStrategy("HIERARCHICAL_V1");
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));
        when(embeddingClient.modelName()).thenReturn("test-embedding");

        SearchResult first = new SearchResult(7L, "sleep.md", "fixed routine", 0.9,
                new EvidenceProvenance("", "vector-7", 2));
        SearchResult second = new SearchResult(8L, "sleep.md", "reduce screens", 0.8,
                new EvidenceProvenance("", "vector-8", 3));
        when(elasticsearchGateway.hybridSearch(any())).thenReturn(List.of(first, second));
        when(evidenceReranker.rerank(eq("sleep support"), any(), eq(12)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        KnowledgeVersionChunk firstChunk = hierarchicalChunk(7L, 21L, 0, "vector-7");
        KnowledgeVersionChunk secondChunk = hierarchicalChunk(8L, 21L, 1, "vector-8");
        when(knowledgeVersionChunkRepository.findAllById(any())).thenReturn(List.of(firstChunk, secondChunk));
        KnowledgeVersionSection section = new KnowledgeVersionSection();
        org.springframework.test.util.ReflectionTestUtils.setField(section, "id", 21L);
        section.setParentKey("parent-sleep");
        section.setSectionPath("睡眠支持 > 日常调整");
        section.setContent("保持固定起床时间，减少睡前屏幕刺激，并记录调整后的变化。");
        when(knowledgeVersionSectionRepository.findAllById(any())).thenReturn(List.of(section));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.content()).isEqualTo(section.getContent());
            assertThat(evidence.provenance().parentKey()).isEqualTo("parent-sleep");
            assertThat(evidence.provenance().vectorId()).isEqualTo("vector-7");
            assertThat(evidence.provenance().sectionPath()).isEqualTo("睡眠支持 > 日常调整");
        });
    }

    private KnowledgeVersionChunk hierarchicalChunk(Long id, Long parentId, int childIndex, String vectorId) {
        KnowledgeVersionChunk chunk = new KnowledgeVersionChunk();
        org.springframework.test.util.ReflectionTestUtils.setField(chunk, "id", id);
        chunk.setParentSectionId(parentId);
        chunk.setChildIndex(childIndex);
        chunk.setVectorId(vectorId);
        chunk.setSource("sleep.md");
        chunk.setSourceIndex(childIndex + 2);
        chunk.setStartOffset(10 + childIndex * 20);
        chunk.setEndOffset(30 + childIndex * 20);
        return chunk;
    }

    @Test
    void retrievesWithElasticsearchKnnBm25RrfAndPostFusionReranking() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("mindcare-knowledge-v1");
        activeVersion.setEmbeddingModel("test-embedding");
        activeVersion.setEmbeddingDimensions(2);
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));
        when(embeddingClient.modelName()).thenReturn("test-embedding");

        SearchResult rrfCandidate = new SearchResult(
                7L,
                "sleep.md",
                "Sleep support guidance.",
                0.0328,
                new EvidenceProvenance("", "vector-7", 2));
        when(elasticsearchGateway.hybridSearch(any(ElasticsearchHybridQuery.class)))
                .thenReturn(List.of(rrfCandidate));
        when(evidenceReranker.rerank(eq("sleep support"), any(), eq(4)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.backend()).isEqualTo("elasticsearch_rrf");
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(7L);
            assertThat(evidence.provenance().knowledgeVersionKey())
                    .isEqualTo(activeVersion.getVersionKey());
            assertThat(evidence.provenance().vectorId()).isEqualTo("vector-7");
        });
        verify(evidenceReranker).rerank(eq("sleep support"), any(), eq(4));
    }

    @Test
    void failsClosedWhenElasticsearchHybridRetrievalIsUnavailable() {
        properties.getKnowledge().setRetrievalMode("ELASTICSEARCH_REQUIRED");
        properties.getKnowledge().setUseElasticsearch(true);
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("mindcare-knowledge-v1");
        activeVersion.setEmbeddingModel("test-embedding");
        activeVersion.setEmbeddingDimensions(2);
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));
        when(embeddingClient.modelName()).thenReturn("test-embedding");
        when(elasticsearchGateway.hybridSearch(any(ElasticsearchHybridQuery.class)))
                .thenThrow(new IllegalStateException("elasticsearch down"));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.backend()).isEqualTo("elasticsearch_rrf");
        assertThat(result.evidence()).isEmpty();
        verify(knowledgeChunkRepository, never()).findAll();
    }

    @Test
    void carriesActiveVersionAndChunkPositionIntoLocalEvidence() {
        properties.getKnowledge().setRetrievalMode("LOCAL_BASELINE");
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(1.0, 0.0));

        KnowledgeVersion activeVersion = new KnowledgeVersion();
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));

        KnowledgeVersionChunk chunk = new KnowledgeVersionChunk();
        chunk.setVectorId("vector-1");
        chunk.setSource("sleep.md");
        chunk.setSourceIndex(2);
        chunk.setContent("Sleep support guidance.");
        chunk.setEmbeddingJson("[1.0, 0.0]");
        when(knowledgeVersionChunkRepository.findByKnowledgeVersionIdOrderBySourceAscSourceIndexAsc(null))
                .thenReturn(List.of(chunk));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.evidence()).singleElement()
                .extracting(SearchResult::provenance)
                .isEqualTo(new EvidenceProvenance(activeVersion.getVersionKey(), "vector-1", 2));
    }

    @Test
    void usesLocalBaselineOnlyWhenItIsExplicitlySelected() {
        properties.getKnowledge().setRetrievalMode("LOCAL_BASELINE");
        when(embeddingClient.embed("sleep support")).thenReturn(List.of());

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setSource("sleep.md");
        chunk.setSourceIndex(0);
        chunk.setContent("Sleep support guidance recommends a consistent sleep routine.");
        when(knowledgeChunkRepository.findAll()).thenReturn(List.of(chunk));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.backend()).isEqualTo("legacy_local_baseline");
        assertThat(result.evidence()).extracting(SearchResult::source).containsExactly("sleep.md");
    }

}
