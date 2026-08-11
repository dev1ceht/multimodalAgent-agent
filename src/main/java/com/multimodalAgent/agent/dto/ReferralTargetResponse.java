package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.UserAccount;

/** Minimal staff identity projection used when assigning a counselor referral. */
public record ReferralTargetResponse(Long id, String username, String displayName) {

    public static ReferralTargetResponse from(UserAccount account) {
        return new ReferralTargetResponse(account.getId(), account.getUsername(), account.getDisplayName());
    }
}
