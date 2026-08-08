package com.multimodalAgent.agent.domain;

public enum ReferralStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(ReferralStatus target) {
        if (target == null || target == this) {
            return target == this;
        }
        return switch (this) {
            case PENDING -> target == ACCEPTED || target == DECLINED || target == CANCELLED;
            case ACCEPTED -> target == COMPLETED || target == CANCELLED;
            case DECLINED, COMPLETED, CANCELLED -> false;
        };
    }
}
