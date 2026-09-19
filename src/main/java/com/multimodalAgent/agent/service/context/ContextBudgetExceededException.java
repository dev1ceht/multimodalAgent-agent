package com.multimodalAgent.agent.service.context;

/** Raised when mandatory context cannot fit in the configured model budget. */
public class ContextBudgetExceededException extends IllegalStateException {

    private final int estimatedTokens;
    private final int inputCeiling;

    public ContextBudgetExceededException(int estimatedTokens, int inputCeiling) {
        super("context_budget_exceeded");
        this.estimatedTokens = estimatedTokens;
        this.inputCeiling = inputCeiling;
    }

    public int estimatedTokens() {
        return estimatedTokens;
    }

    public int inputCeiling() {
        return inputCeiling;
    }
}
