package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.multimodalAgent.agent.domain.RiskLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskResponsePolicyTests {

    @Test
    void defaultPolicyKeepsOnlyHighRiskInHumanFollowUpAndNotificationFlow() {
        RiskCaseSlaProperties properties = new RiskCaseSlaProperties();

        assertThat(properties.opensCase(RiskLevel.NONE)).isFalse();
        assertThat(properties.opensCase(RiskLevel.LOW)).isFalse();
        assertThat(properties.opensCase(RiskLevel.MEDIUM)).isFalse();
        assertThat(properties.opensCase(RiskLevel.HIGH)).isTrue();
        assertThat(properties.notifiesStaff(RiskLevel.NONE)).isFalse();
        assertThat(properties.notifiesStaff(RiskLevel.LOW)).isFalse();
        assertThat(properties.notifiesStaff(RiskLevel.MEDIUM)).isFalse();
        assertThat(properties.notifiesStaff(RiskLevel.HIGH)).isTrue();
    }

    @Test
    void policyCanDisableNotificationWithoutDisablingHumanFollowUp() {
        RiskCaseSlaProperties properties = new RiskCaseSlaProperties();
        properties.setResponsePolicy(Map.of(
                RiskLevel.HIGH,
                new RiskResponseRule(true, false)));

        assertThat(properties.opensCase(RiskLevel.HIGH)).isTrue();
        assertThat(properties.notifiesStaff(RiskLevel.HIGH)).isFalse();
        assertThat(properties.opensCase(RiskLevel.LOW)).isFalse();
    }

    @Test
    void lowerRiskLevelsCannotBeEnabledForHumanFollowUpWithoutASeparateDomainDecision() {
        RiskCaseSlaProperties properties = new RiskCaseSlaProperties();

        assertThatThrownBy(() -> properties.setResponsePolicy(Map.of(
                RiskLevel.MEDIUM,
                new RiskResponseRule(true, false))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
