package com.multimodalAgent.agent.domain;

import java.util.Collection;

/**
 * Roles used by the campus platform. The ADMIN authority is kept as the
 * system-administrator compatibility authority for existing accounts.
 */
public enum UserRole {
    STUDENT("ROLE_USER"),
    SYSTEM_ADMIN("ROLE_ADMIN"),
    COUNSELOR("ROLE_COUNSELOR"),
    PSYCHOLOGY_CENTER("ROLE_PSYCHOLOGY_CENTER"),
    SCHOOL_ADMIN("ROLE_SCHOOL_ADMIN");

    private final String authority;

    UserRole(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public static boolean isStudentAccount(Collection<String> roles) {
        if (roles == null || !roles.contains(STUDENT.authority())) {
            return false;
        }
        return roles.stream().noneMatch(role ->
                SYSTEM_ADMIN.authority().equals(role)
                        || COUNSELOR.authority().equals(role)
                        || PSYCHOLOGY_CENTER.authority().equals(role)
                        || SCHOOL_ADMIN.authority().equals(role));
    }
}
