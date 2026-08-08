package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.service.DeliveryIdempotency;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 邮件预警实现。
 *
 * <p>高风险报告触发后，把摘要信息发送给配置的辅导员或心理中心邮箱。</p>
 */
public class SmtpAlertNotifier implements AlertNotifier {

    private final JavaMailSender mailSender;
    private final multimodalAgentProperties properties;

    public SmtpAlertNotifier(JavaMailSender mailSender, multimodalAgentProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void notify(AlertRecord alertRecord, PsychologicalReport report, String idempotencyKey) {
        // SMTP has no provider-level idempotency contract; the durable task lease controls retries.
        DeliveryIdempotency.requireKey(idempotencyKey);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMcp().getEmail().getFrom());
        message.setTo(alertRecord.getRecipient());
        message.setSubject("【高危心理预警】学生用户 %s 存在高风险信号".formatted(report.getUser().getUsername()));
        message.setText("""
                系统在对话中监测到 1 名学生出现高风险心理状态，请及时关注并干预。

                【预警信息如下】
                报告ID：%s
                用户ID：%s
                学生：%s
                对话内容：%s
                情绪判定：%s
                综合情绪得分：%.2f
                风险等级：%s
                判断摘要：%s

                """.formatted(
                report.getId(),
                report.getUser().getUsername(),
                report.getUser().getDisplayName(),
                report.getContent(),
                report.getEmotion(),
                report.getEmotionScore(),
                report.getRiskLevel(),
                report.getSummary()));
        mailSender.send(message);
    }

    @Override
    public void notifyRiskCaseEscalation(RiskCase riskCase, String recipient, String idempotencyKey) {
        DeliveryIdempotency.requireKey(idempotencyKey);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMcp().getEmail().getFrom());
        message.setTo(recipient);
        String username = riskCase.getStudentUser() == null
                ? "unknown-student"
                : riskCase.getStudentUser().getUsername();
        message.setSubject("高风险案件逾期升级：案件 %s".formatted(riskCase.getId()));
        message.setText("""
                系统检测到一条高风险案件已超过人工响应时限，请尽快跟进。

                案件ID：%s
                学生账号：%s
                当前状态：%s
                风险等级：%s
                响应截止时间：%s
                """.formatted(
                riskCase.getId(),
                username,
                riskCase.getStatus(),
                riskCase.getRiskLevel(),
                riskCase.getSlaDueAt()));
        mailSender.send(message);
    }
}
