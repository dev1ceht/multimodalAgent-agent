package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class KnowledgeTextExtractorTests {

    @Test
    void extractsUtf8TextAndPreservesTheParserContract() {
        KnowledgeTextExtractor extractor = new KnowledgeTextExtractor(1024, 100);

        assertThat(extractor.extract("guide.txt", "睡眠支持\nKeep a routine.".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("睡眠支持\nKeep a routine.");
        assertThat(KnowledgeTextExtractor.PARSER_VERSION).isEqualTo("pdfbox-3.0.3-text-v1");
    }

    @Test
    void extractsPdfTextAndSeparatesPages() throws IOException {
        byte[] pdf = pdfWithText("first page", "second page");

        String text = new KnowledgeTextExtractor(1024 * 1024, 1000).extract("guide.pdf", pdf);

        assertThat(text).contains("first page", "second page");
        assertThat(text).contains("\n\f\n");
    }

    @Test
    void rejectsUnsupportedEmptyAndOversizedInput() {
        KnowledgeTextExtractor extractor = new KnowledgeTextExtractor(4, 100);

        assertThatThrownBy(() -> extractor.extract("guide.csv", "text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("UNSUPPORTED_FORMAT");
        assertThatThrownBy(() -> extractor.extract("guide.txt", new byte[0]))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("EMPTY_FILE");
        assertThatThrownBy(() -> extractor.extract("guide.txt", "12345".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void rejectsBlankAndOverlongExtractedText() {
        assertThatThrownBy(() -> new KnowledgeTextExtractor(1024, 100).extract("guide.txt", " \n".getBytes()))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("NO_EXTRACTED_TEXT");
        assertThatThrownBy(() -> new KnowledgeTextExtractor(1024, 3).extract("guide.txt", "text".getBytes()))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("PARSED_TEXT_TOO_LARGE");
    }

    private byte[] pdfWithText(String first, String second) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPage(document, first);
            addPage(document, second);
            document.save(output);
            return output.toByteArray();
        }
    }

    private void addPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 700);
            content.showText(text);
            content.endText();
        }
    }
}
