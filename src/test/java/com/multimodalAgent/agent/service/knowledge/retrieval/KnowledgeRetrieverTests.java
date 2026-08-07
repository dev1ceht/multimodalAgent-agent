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
import com.multimodalAgent.agent.domain.KnowledgeVersionStatus;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeVersionRepository;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.ChromaGateway;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
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
    private ChromaGateway chromaGateway;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EvaluationTraceService evaluationTraceService;

    @Mock
    private EvidenceReranker evidenceReranker;

    private multimodalAgentProperties properties;
    private KnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        properties = new multimodalAgentProperties();
        properties.getKnowledge().setUseChroma(true);
        properties.getKnowledge().setRetrievalMode("CHROMA_REQUIRED");
        properties.getKnowledge().setTopK(4);
        retriever = new KnowledgeRetriever(
                knowledgeChunkRepository,
                knowledgeVersionRepository,
                knowledgeVersionChunkRepository,
                properties,
                chromaGateway,
                embeddingClient,
                new ObjectMapper(),
                evaluationTraceService,
                evidenceReranker);
    }

    @Test
    void returnsFailedInsteadOfSilentlyUsingLocalResultsWhenChromaIsUnavailable() {
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("knowledge_test_version");
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));
        when(chromaGateway.query(any(String.class), any(), eq(12)))
                .thenThrow(new IllegalStateException("chroma down"));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.backend()).isEqualTo("chroma");
        assertThat(result.evidence()).isEmpty();
        verify(knowledgeChunkRepository, never()).findAll();
    }

    @Test
    void expandsChromaCandidatesBeforeSelectingFinalEvidence() {
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("knowledge_test_version");
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));

        SearchResult candidate = new SearchResult(null, "sleep.md", "Sleep support guidance.", 0.8);
        when(chromaGateway.query(any(String.class), any(), eq(12))).thenReturn(List.of(candidate));
        when(evidenceReranker.rerank("sleep support", List.of(candidate), 4))
                .thenReturn(List.of(candidate));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.evidence()).containsExactly(candidate);
        verify(evidenceReranker).rerank("sleep support", List.of(candidate), 4);
    }

    @Test
    void canDisableRerankingWithoutChangingTheChromaFailurePolicy() {
        properties.getKnowledge().setRerankEnabled(false);
        KnowledgeVersion activeVersion = new KnowledgeVersion();
        activeVersion.setCollectionName("knowledge_test_version");
        when(knowledgeVersionRepository.findTopByStatusOrderByActivatedAtDesc(KnowledgeVersionStatus.ACTIVE))
                .thenReturn(Optional.of(activeVersion));
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));

        SearchResult candidate = new SearchResult(null, "sleep.md", "Sleep support guidance.", 0.8);
        when(chromaGateway.query(any(String.class), any(), eq(4))).thenReturn(List.of(candidate));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.READY);
        assertThat(result.evidence()).containsExactly(candidate);
        verify(evidenceReranker, never()).rerank(any(String.class), any(), any(Integer.class));
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

    @Test
    void rejectsChromaRequiredWhenChromaWasDisabled() {
        properties.getKnowledge().setUseChroma(false);

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.reason()).contains("Chroma");
        verify(embeddingClient, never()).embed(any());
    }
}
