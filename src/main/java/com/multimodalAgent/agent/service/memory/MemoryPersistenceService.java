package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.domain.*;
import com.multimodalAgent.agent.repository.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryPersistenceService {
    private final MemoryFactRepository facts;
    private final MemoryTopicRepository topics;
    private final MemoryFactTopicRepository memberships;
    private final MemoryRelationRepository relations;

    public MemoryPersistenceService(MemoryFactRepository facts, MemoryTopicRepository topics,
                                    MemoryFactTopicRepository memberships, MemoryRelationRepository relations) {
        this.facts = facts;
        this.topics = topics;
        this.memberships = memberships;
        this.relations = relations;
    }

    @Transactional
    public MemoryProjectionBatch persist(LongTermMemoryTask task, CompiledMemory compiled) {
        List<MemoryFact> storedFacts = new ArrayList<>(
                facts.findBySourceMessageIdOrderByFactOrdinalAsc(task.getSourceMessageId()));
        if (storedFacts.isEmpty()) {
            for (int i = 0; i < compiled.facts().size(); i++) {
                CompiledFact source = compiled.facts().get(i);
                MemoryFact fact = new MemoryFact();
                fact.setUserId(task.getUserId());
                fact.setSessionId(task.getSessionId());
                fact.setSourceMessageId(task.getSourceMessageId());
                fact.setFactOrdinal(i);
                fact.setContent(source.content());
                fact.setOccurredAt(source.occurredAt());
                storedFacts.add(facts.save(fact));
            }
        }

        Map<String, MemoryTopic> storedTopics = new LinkedHashMap<>();
        for (CompiledTopic source : compiled.topics()) {
            MemoryTopic topic = topics.findByUserIdAndTopicKey(task.getUserId(), source.key())
                    .orElseGet(MemoryTopic::new);
            topic.setUserId(task.getUserId());
            topic.setTopicKey(source.key());
            topic.setTitle(source.title());
            topic.setSummary(source.summary());
            topic.setUpdatedAt(Instant.now());
            storedTopics.put(source.key(), topics.save(topic));
        }

        List<MemoryFactTopic> storedMemberships = new ArrayList<>();
        List<MemoryProjectionBatch.Membership> projectedMemberships = new ArrayList<>();
        for (CompiledMembership source : compiled.memberships()) {
            MemoryFact fact = resolveFact(source.fact(), storedFacts, task.getUserId());
            MemoryTopic topic = storedTopics.get(source.topicKey());
            if (fact == null || topic == null) continue;
            projectedMemberships.add(new MemoryProjectionBatch.Membership(fact.getId(), topic.getId()));
            if (!memberships.existsByFactIdAndTopicId(fact.getId(), topic.getId())) {
                MemoryFactTopic membership = new MemoryFactTopic();
                membership.setFactId(fact.getId());
                membership.setTopicId(topic.getId());
                storedMemberships.add(memberships.save(membership));
            }
        }

        List<MemoryRelation> storedRelations = new ArrayList<>();
        List<MemoryProjectionBatch.Relation> projectedRelations = new ArrayList<>();
        for (CompiledRelation source : compiled.relations()) {
            MemoryFact from = resolveFact(source.source(), storedFacts, task.getUserId());
            MemoryFact to = resolveFact(source.target(), storedFacts, task.getUserId());
            if (from == null || to == null || from.getId().equals(to.getId())) continue;
            projectedRelations.add(new MemoryProjectionBatch.Relation(
                    from.getId(), to.getId(), source.type(), source.confidence()));
            if (!relations.existsBySourceFactIdAndTargetFactIdAndType(from.getId(), to.getId(), source.type())) {
                MemoryRelation relation = new MemoryRelation();
                relation.setUserId(task.getUserId());
                relation.setSourceFactId(from.getId());
                relation.setTargetFactId(to.getId());
                relation.setType(source.type());
                relation.setConfidence(source.confidence());
                storedRelations.add(relations.save(relation));
            }
        }

        return new MemoryProjectionBatch(task.getUserId(),
                storedFacts.stream().map(f -> new MemoryProjectionBatch.Fact(
                        f.getId(), f.getSessionId(), f.getContent(), f.getOccurredAt())).toList(),
                storedTopics.values().stream().map(t -> new MemoryProjectionBatch.Topic(
                        t.getId(), t.getTopicKey(), t.getTitle(), t.getSummary())).toList(),
                projectedMemberships,
                projectedRelations);
    }

    private MemoryFact resolveFact(String reference, List<MemoryFact> newFacts, Long userId) {
        try {
            if (reference.startsWith("new:")) {
                int index = Integer.parseInt(reference.substring(4));
                return index >= 0 && index < newFacts.size() ? newFacts.get(index) : null;
            }
            if (reference.startsWith("existing:")) {
                return facts.findById(Long.parseLong(reference.substring(9)))
                        .filter(f -> f.getUserId().equals(userId)).orElse(null);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}
