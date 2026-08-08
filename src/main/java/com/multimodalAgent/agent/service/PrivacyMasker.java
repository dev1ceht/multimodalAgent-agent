package com.multimodalAgent.agent.service;

/** Centralized masking rules for profile data that must never be returned in clear text. */
public final class PrivacyMasker {

    private PrivacyMasker() {
    }

    public static String phone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.contains("*")) {
            return normalized;
        }
        String digits = normalized.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    public static String emergencyContact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.contains("*")) {
            return normalized;
        }
        if (normalized.length() <= 2) {
            return "**";
        }
        return "*".repeat(normalized.length() - 2) + normalized.substring(normalized.length() - 2);
    }
}
