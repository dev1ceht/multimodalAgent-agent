package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.service.DeliveryIdempotency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志预警实现。
 *
 * <p>用于本地演示或无 SMTP 环境时验证高风险链路是否被触发。</p>
 */
public class LogAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LogAlertNotifier.class);

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey) {
        String deliveryKey = DeliveryIdempotency.requireKey(idempotencyKey);
        log.warn(
                "High risk alert dry-run: recipient={}, reportId={}, user={}, idempotencyKey={}, summary={}",
                alertRecord.getRecipient(),
                report.getId(),
                report.getUser().getUsername(),
                deliveryKey,
                report.getSummary());
    }

    @Override
    public void notifyRiskCaseEscalation(RiskCase riskCase, String recipient, String idempotencyKey) {
        String deliveryKey = DeliveryIdempotency.requireKey(idempotencyKey);
        log.warn(
                "Risk case overdue escalation dry-run: recipient={}, riskCaseId={}, student={}, status={}, dueAt={}, idempotencyKey={}",
                recipient,
                riskCase.getId(),
                riskCase.getStudentUser() == null ? null : riskCase.getStudentUser().getUsername(),
                riskCase.getStatus(),
                riskCase.getSlaDueAt(),
                deliveryKey);
    }
}
