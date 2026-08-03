package com.multimodalAgent.agent.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalExcelReportWriterTests {

    @TempDir
    Path tempDir;

    @Test
    void retryUpdatesExistingReportRowInsteadOfAppendingDuplicate() throws Exception {
        multimodalAgentProperties properties = new multimodalAgentProperties();
        Path workbookPath = tempDir.resolve("reports.xlsx");
        properties.getMcp().getExcel().setLocalPath(workbookPath.toString());
        LocalExcelReportWriter writer = new LocalExcelReportWriter(properties);

        writer.writePayload(payload("first summary"));
        writer.writePayload(payload("updated summary"));

        try (InputStream input = Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(7.0);
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("updated summary");
        }
    }

    private Map<String, Object> payload(String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", 7L);
        payload.put("userId", 11L);
        payload.put("username", "student");
        payload.put("sessionId", "session-1");
        payload.put("needsRag", true);
        payload.put("emotion", "HIGH_RISK");
        payload.put("emotionScore", 4.0);
        payload.put("riskLevel", "HIGH");
        payload.put("confidence", 0.9);
        payload.put("summary", summary);
        payload.put("emotionTags", "[]");
        payload.put("content", "content");
        payload.put("createdAt", "2026-08-03T00:00:00Z");
        return payload;
    }
}
