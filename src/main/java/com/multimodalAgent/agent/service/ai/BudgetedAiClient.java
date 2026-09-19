package com.multimodalAgent.agent.service.ai;

import com.multimodalAgent.agent.service.context.ContextBudgetService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/** Applies the same input budget to routing, assessment, RAG and answer calls. */
public final class BudgetedAiClient implements AiClient {

    private final AiClient delegate;
    private final ContextBudgetService contextBudget;

    public BudgetedAiClient(AiClient delegate, ContextBudgetService contextBudget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.contextBudget = Objects.requireNonNull(contextBudget, "contextBudget");
    }

    @Override
    public String complete(List<AiMessage> messages) {
        return delegate.complete(fit(messages));
    }

    @Override
    public String completeJson(List<AiMessage> messages, Map<String, Object> schema) {
        return delegate.completeJson(fit(messages), schema);
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        return Flux.defer(() -> delegate.stream(fit(messages)));
    }

    private List<AiMessage> fit(List<AiMessage> messages) {
        return contextBudget.fitAiMessages(messages).messages();
    }
}
