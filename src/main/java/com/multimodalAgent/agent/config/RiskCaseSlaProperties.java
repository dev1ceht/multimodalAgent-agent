package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.domain.RiskLevel;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-controlled SLA windows and risk response actions. */
@ConfigurationProperties(prefix = "multimodal-agent.risk-case.sla")
public class RiskCaseSlaProperties {

    private Duration highRiskCaseResponse = Duration.ofHours(2);
    private Duration referralResponse = Duration.ofHours(72);
    private Map<RiskLevel, RiskResponseRule> responsePolicy = defaultResponsePolicy();

    public Duration getHighRiskCaseResponse() {
        return highRiskCaseResponse;
    }

    public void setHighRiskCaseResponse(Duration highRiskCaseResponse) {
        this.highRiskCaseResponse = requirePositive(highRiskCaseResponse, "high-risk case response");
    }

    public Duration getReferralResponse() {
        return referralResponse;
    }

    public void setReferralResponse(Duration referralResponse) {
        this.referralResponse = requirePositive(referralResponse, "referral response");
    }

    public Map<RiskLevel, RiskResponseRule> getResponsePolicy() {
        return responsePolicy;
    }

    public void setResponsePolicy(Map<RiskLevel, RiskResponseRule> responsePolicy) {
        if (responsePolicy == null || responsePolicy.isEmpty()) {
            this.responsePolicy = defaultResponsePolicy();
            return;
        }
        responsePolicy.forEach((riskLevel, rule) -> {
            if (riskLevel == null || rule == null) {
                throw new IllegalArgumentException("Risk response policy entries must not be null");
            }
            if (riskLevel != RiskLevel.HIGH
                    && (rule.isOpensCase() || rule.isNotifiesStaff())) {
                throw new IllegalArgumentException(
                        "Only HIGH risk may open cases or notify staff in the current policy");
            }
        });
        this.responsePolicy = new EnumMap<>(RiskLevel.class);
        this.responsePolicy.putAll(responsePolicy);
    }

    public boolean opensCase(RiskLevel riskLevel) {
        return ruleFor(riskLevel).isOpensCase();
    }

    public boolean notifiesStaff(RiskLevel riskLevel) {
        return ruleFor(riskLevel).isNotifiesStaff();
    }

    private RiskResponseRule ruleFor(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return new RiskResponseRule(false, false);
        }
        RiskResponseRule rule = responsePolicy.get(riskLevel);
        return rule == null ? new RiskResponseRule(false, false) : rule;
    }

    private static Map<RiskLevel, RiskResponseRule> defaultResponsePolicy() {
        EnumMap<RiskLevel, RiskResponseRule> defaults = new EnumMap<>(RiskLevel.class);
        defaults.put(RiskLevel.NONE, new RiskResponseRule(false, false));
        defaults.put(RiskLevel.LOW, new RiskResponseRule(false, false));
        defaults.put(RiskLevel.MEDIUM, new RiskResponseRule(false, false));
        defaults.put(RiskLevel.HIGH, new RiskResponseRule(true, true));
        return defaults;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " duration must be positive");
        }
        return value;
    }
}
