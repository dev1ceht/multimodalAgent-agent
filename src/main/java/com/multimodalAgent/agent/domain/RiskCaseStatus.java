package com.multimodalAgent.agent.domain;

public enum RiskCaseStatus {
    OPEN,
    ACKNOWLEDGED,
    REFERRED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    public boolean canTransitionTo(RiskCaseStatus target) {
        if (target == null || target == this) {
            return target == this;
        }
        return switch (this) {
            case OPEN -> target == ACKNOWLEDGED || target == REFERRED
                    || target == IN_PROGRESS || target == CLOSED;
            case ACKNOWLEDGED -> target == REFERRED || target == IN_PROGRESS || target == CLOSED;
            case REFERRED -> target == IN_PROGRESS || target == CLOSED;
            case IN_PROGRESS -> target == REFERRED || target == RESOLVED;
            case RESOLVED -> target == REFERRED || target == IN_PROGRESS || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
