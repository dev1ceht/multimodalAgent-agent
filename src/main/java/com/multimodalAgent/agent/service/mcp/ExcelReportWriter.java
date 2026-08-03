package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * 心理报告写入 Excel 的工具接口。
 *
 * <p>本地文件写入和远程 MCP 写入都实现这个接口。</p>
 */
public interface ExcelReportWriter {

    void write(PsychologicalReport report);

    /**
     * 带幂等键的写入入口；旧适配器可以只实现基础入口。
     */
    default void write(PsychologicalReport report, String idempotencyKey) {
        write(report);
    }
}
