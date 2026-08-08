package com.multimodalAgent.agent.domain;

/** Stable business actions that must be traceable in the audit log. */
public enum AuditAction {
    KNOWLEDGE_INGEST,
    KNOWLEDGE_FILE_INGEST,
    KNOWLEDGE_STATUS_VIEW,
    REPORT_SELF_VIEW,
    REPORT_LIST_VIEW,
    EXCEL_RECORD_VIEW,
    ALERT_LIST_VIEW,
    CONVERSATION_VIEW,
    PROFILE_VIEW,
    PROFILE_UPDATE,
    CONSENT_LIST_VIEW,
    CONSENT_GRANT,
    ACCESS_DENIED
}
