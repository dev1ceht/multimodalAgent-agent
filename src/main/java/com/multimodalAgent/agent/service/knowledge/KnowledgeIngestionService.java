package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.domain.KnowledgeChunk;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import com.multimodalAgent.agent.repository.KnowledgeDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/** 启动时把内置或旧格式知识导入 canonical 知识目录。 */
@Service
public class KnowledgeIngestionService {

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeService knowledgeService;

    public KnowledgeIngestionService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeService knowledgeService
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeService = knowledgeService;
    }

    public void ingestClasspathKnowledgeIfEmpty() {
        if (knowledgeDocumentRepository.count() > 0) {
            return;
        }

        try {
            List<KnowledgeDocumentInput> legacy = legacyDocuments();
            if (!legacy.isEmpty()) {
                knowledgeService.ingestBatch(legacy);
                return;
            }

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:knowledge/*.*");
            List<KnowledgeDocumentInput> bundled = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                bundled.add(new KnowledgeDocumentInput(filename, content));
            }
            knowledgeService.ingestBatch(bundled);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load bundled knowledge base", exception);
        }
    }

    private List<KnowledgeDocumentInput> legacyDocuments() {
        if (knowledgeChunkRepository.count() == 0) {
            return List.of();
        }
        Map<String, List<String>> contents = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : knowledgeChunkRepository.findAllByOrderBySourceAscSourceIndexAsc()) {
            contents.computeIfAbsent(chunk.getSource(), ignored -> new ArrayList<>())
                    .add(chunk.getContent());
        }
        return contents.entrySet().stream()
                .map(entry -> new KnowledgeDocumentInput(
                        entry.getKey(),
                        String.join("\n\n", entry.getValue())))
                .toList();
    }
}
