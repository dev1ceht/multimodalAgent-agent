package com.multimodalAgent.agent.service.knowledge;

/** Structured parser failure used by the asynchronous knowledge worker. */
public class KnowledgeParseException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public KnowledgeParseException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public KnowledgeParseException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
