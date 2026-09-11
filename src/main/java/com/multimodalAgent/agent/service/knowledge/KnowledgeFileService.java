package com.multimodalAgent.agent.service.knowledge;

import org.springframework.stereotype.Service;

@Service
/**
 * 管理员文件上传知识库服务。
 *
 * <p>负责文件大小校验、类型识别和文本抽取，抽取后的文本交给 KnowledgeService 处理。</p>
 */
public class KnowledgeFileService {

    private final KnowledgeService knowledgeService;
    private final KnowledgeTextExtractor textExtractor;

    public KnowledgeFileService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
        this.textExtractor = new KnowledgeTextExtractor();
    }

    public int ingest(String filename, byte[] bytes) {
        // 文件上传入口只负责校验和抽取文本，真正切块、向量化、落库交给 KnowledgeService。
        String source = sanitizeSource(filename);
        String text;
        try {
            text = textExtractor.extract(source, bytes);
        } catch (KnowledgeParseException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        return knowledgeService.ingest(source, text);
    }

    private String sanitizeSource(String filename) {
        String source = filename == null || filename.isBlank() ? "uploaded-knowledge" : filename.trim();
        // source 会进入数据库和后台列表，去掉路径分隔符避免显示本地路径。
        source = source.replaceAll("[\\\\/]+", "-");
        return source.length() > 180 ? source.substring(source.length() - 180) : source;
    }
}
