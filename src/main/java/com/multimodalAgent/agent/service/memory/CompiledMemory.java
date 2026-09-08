package com.multimodalAgent.agent.service.memory;

import java.util.List;

public record CompiledMemory(
        List<CompiledFact> facts,
        List<CompiledTopic> topics,
        List<CompiledRelation> relations,
        List<CompiledMembership> memberships
) {
    public CompiledMemory {
        facts = List.copyOf(facts);
        topics = List.copyOf(topics);
        relations = List.copyOf(relations);
        memberships = List.copyOf(memberships);
    }
}
