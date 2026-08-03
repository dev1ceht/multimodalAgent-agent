package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;

/**
 * 高风险预警通知接口。
 *
 * <p>具体实现可以是日志、SMTP 邮件或 HTTP MCP 服务。</p>
 */
public interface AlertNotifier {

    void notify(AlertRecord alertRecord, PsychologicalReport report);

    /**
     * 带幂等键的通知入口；不支持幂等键的通知方式仍可复用旧实现。
     */
    default void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey) {
        notify(alertRecord, report);
    }
}
