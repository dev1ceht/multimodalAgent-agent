package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Bounded, format-aware text extraction shared by the legacy and asynchronous upload paths.
 * It deliberately does not perform OCR or any downstream indexing work.
 */
public class KnowledgeTextExtractor {

    public static final String PARSER_VERSION = "pdfbox-3.0.3-text-v1";

    private final long maxBytes;
    private final long maxCharacters;
    private final long timeoutNanos;

    public KnowledgeTextExtractor() {
        this(10L * 1024 * 1024, 2_000_000L, 120);
    }

    public KnowledgeTextExtractor(multimodalAgentProperties properties) {
        this(properties.getKnowledge().getUpload().getMaxFileBytes(),
                properties.getKnowledge().getUpload().getParserMaxCharacters(),
                properties.getKnowledge().getUpload().getParserTimeoutSeconds());
    }

    public KnowledgeTextExtractor(long maxBytes, long maxCharacters) {
        this(maxBytes, maxCharacters, 120);
    }

    public KnowledgeTextExtractor(long maxBytes, long maxCharacters, long timeoutSeconds) {
        this.maxBytes = Math.max(1, maxBytes);
        this.maxCharacters = Math.max(1, maxCharacters);
        this.timeoutNanos = Math.max(1, timeoutSeconds) * 1_000_000_000L;
    }

    public String extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new KnowledgeParseException("EMPTY_FILE", "文件内容为空", false);
        }
        if (bytes.length > maxBytes) {
            throw new KnowledgeParseException("FILE_TOO_LARGE", "文件超过大小限制", false);
        }
        return extractSupported(filename, bytes, deadline());
    }

    public String extract(String filename, InputStream input) {
        if (input == null) {
            throw new KnowledgeParseException("EMPTY_FILE", "文件内容为空", false);
        }
        long deadline = deadline();
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                ensureWithinDeadline(deadline);
                total += read;
                if (total > maxBytes) {
                    throw new KnowledgeParseException("FILE_TOO_LARGE", "文件超过大小限制", false);
                }
                output.write(buffer, 0, read);
            }
            return extractSupported(filename, output.toByteArray(), deadline);
        } catch (KnowledgeParseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new KnowledgeParseException("OBJECT_READ_FAILED", "无法读取原始文件", true, exception);
        }
    }

    private String extractSupported(String filename, byte[] bytes, long deadline) {
        ensureWithinDeadline(deadline);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".pdf")) {
            text = extractPdf(bytes, deadline);
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new KnowledgeParseException("UNSUPPORTED_FORMAT", "仅支持 PDF、Markdown 和 txt 文件", false);
        }
        ensureWithinDeadline(deadline);
        if (text.length() > maxCharacters) {
            throw new KnowledgeParseException("PARSED_TEXT_TOO_LARGE", "解析文本超过字符数限制", false);
        }
        if (text.isBlank()) {
            throw new KnowledgeParseException("NO_EXTRACTED_TEXT", "没有从文件中解析出可用文本", false);
        }
        return text;
    }

    private String extractPdf(byte[] bytes, long deadline) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                ensureWithinDeadline(deadline);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (!text.isEmpty()) {
                    text.append("\n\f\n");
                }
                text.append(stripper.getText(document).strip());
                ensureWithinDeadline(deadline);
                if (text.length() > maxCharacters) {
                    throw new KnowledgeParseException("PARSED_TEXT_TOO_LARGE", "解析文本超过字符数限制", false);
                }
            }
            return text.toString();
        } catch (KnowledgeParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new KnowledgeParseException("PDF_PARSE_FAILED", "PDF 文本解析失败", false, exception);
        }
    }

    private long deadline() {
        long now = System.nanoTime();
        long deadline = now + timeoutNanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private void ensureWithinDeadline(long deadline) {
        if (System.nanoTime() - deadline > 0) {
            throw new KnowledgeParseException("PARSER_TIMEOUT", "文件解析超过时间限制", true);
        }
    }
}
