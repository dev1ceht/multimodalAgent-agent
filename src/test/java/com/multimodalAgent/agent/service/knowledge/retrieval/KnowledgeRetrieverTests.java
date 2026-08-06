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
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.knowledge.ChromaGateway;
import com.multimodalAgent.agent.service.knowledge.EmbeddingClient;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import java.util.List;
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
    private ChromaGateway chromaGateway;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EvaluationTraceService evaluationTraceService;

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
                properties,
                chromaGateway,
                embeddingClient,
                new ObjectMapper(),
                evaluationTraceService);
    }

    @Test
    void returnsFailedInsteadOfSilentlyUsingLocalResultsWhenChromaIsUnavailable() {
        when(embeddingClient.embed("sleep support")).thenReturn(List.of(0.1, 0.2));
        when(chromaGateway.query(any(), eq(4))).thenThrow(new IllegalStateException("chroma down"));

        RetrievalResult result = retriever.retrieve(new RetrievalQuery("sleep support", 4));

        assertThat(result.status()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(result.backend()).isEqualTo("chroma");
        assertThat(result.evidence()).isEmpty();
        verify(knowledgeChunkRepository, never()).findAll();
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
        assertThat(result.backend()).isEqualTo("local_baseline");
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
