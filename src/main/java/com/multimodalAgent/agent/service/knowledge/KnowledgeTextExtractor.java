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

    public KnowledgeTextExtractor() {
        this(10L * 1024 * 1024, 2_000_000L);
    }

    public KnowledgeTextExtractor(multimodalAgentProperties properties) {
        this(properties.getKnowledge().getUpload().getMaxFileBytes(),
                properties.getKnowledge().getUpload().getParserMaxCharacters());
    }

    public KnowledgeTextExtractor(long maxBytes, long maxCharacters) {
        this.maxBytes = Math.max(1, maxBytes);
        this.maxCharacters = Math.max(1, maxCharacters);
    }

    public String extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new KnowledgeParseException("EMPTY_FILE", "文件内容为空", false);
        }
        if (bytes.length > maxBytes) {
            throw new KnowledgeParseException("FILE_TOO_LARGE", "文件超过大小限制", false);
        }
        return extractSupported(filename, bytes);
    }

    public String extract(String filename, InputStream input) {
        if (input == null) {
            throw new KnowledgeParseException("EMPTY_FILE", "文件内容为空", false);
        }
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new KnowledgeParseException("FILE_TOO_LARGE", "文件超过大小限制", false);
                }
                output.write(buffer, 0, read);
            }
            return extract(filename, output.toByteArray());
        } catch (KnowledgeParseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new KnowledgeParseException("OBJECT_READ_FAILED", "无法读取原始文件", true, exception);
        }
    }

    private String extractSupported(String filename, byte[] bytes) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".pdf")) {
            text = extractPdf(bytes);
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new KnowledgeParseException("UNSUPPORTED_FORMAT", "仅支持 PDF、Markdown 和 txt 文件", false);
        }
        if (text.length() > maxCharacters) {
            throw new KnowledgeParseException("PARSED_TEXT_TOO_LARGE", "解析文本超过字符数限制", false);
        }
        if (text.isBlank()) {
            throw new KnowledgeParseException("NO_EXTRACTED_TEXT", "没有从文件中解析出可用文本", false);
        }
        return text;
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (!text.isEmpty()) {
                    text.append("\n\f\n");
                }
                text.append(stripper.getText(document).strip());
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
}
