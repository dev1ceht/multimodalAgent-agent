package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.security.CurrentUser;
import java.util.List;

public record CurrentAccountResponse(
        Long id,
        String username,
        String displayName,
        List<String> roles
) {
    public static CurrentAccountResponse from(CurrentUser user) {
        List<String> roles = user.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        return new CurrentAccountResponse(user.getId(), user.getUsername(), user.getDisplayName(), roles);
    }
}
