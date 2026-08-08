package com.multimodalAgent.agent.config;

/** Actions permitted for one assessed risk level. */
public class RiskResponseRule {

    private boolean opensCase;
    private boolean notifiesStaff;

    public RiskResponseRule() {
    }

    public RiskResponseRule(boolean opensCase, boolean notifiesStaff) {
        this.opensCase = opensCase;
        this.notifiesStaff = notifiesStaff;
    }

    public boolean isOpensCase() {
        return opensCase;
    }

    public void setOpensCase(boolean opensCase) {
        this.opensCase = opensCase;
    }

    public boolean isNotifiesStaff() {
        return notifiesStaff;
    }

    public void setNotifiesStaff(boolean notifiesStaff) {
        this.notifiesStaff = notifiesStaff;
    }
}
