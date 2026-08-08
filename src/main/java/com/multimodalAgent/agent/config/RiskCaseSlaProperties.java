package com.multimodalAgent.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-controlled response windows for high-risk follow-up work. */
@ConfigurationProperties(prefix = "multimodal-agent.risk-case.sla")
public class RiskCaseSlaProperties {

    private Duration highRiskCaseResponse = Duration.ofHours(2);
    private Duration referralResponse = Duration.ofHours(72);

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

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " duration must be positive");
        }
        return value;
    }
}
