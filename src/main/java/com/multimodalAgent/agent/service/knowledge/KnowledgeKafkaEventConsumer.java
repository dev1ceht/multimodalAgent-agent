package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Persists Kafka messages into the inbox; parsing and indexing happen in bounded workers. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeKafkaEventConsumer {

    private final KnowledgeInboxService inboxService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final multimodalAgentProperties properties;

    public KnowledgeKafkaEventConsumer(
            KnowledgeInboxService inboxService,
            KafkaTemplate<String, String> kafkaTemplate,
            multimodalAgentProperties properties) {
        this.inboxService = inboxService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${multimodal-agent.knowledge.kafka.parse-topic}",
            containerFactory = "knowledgeKafkaListenerContainerFactory")
    public void consumeParse(ConsumerRecord<String, String> record) {
        consume(record, KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED);
    }

    @KafkaListener(
            topics = "${multimodal-agent.knowledge.kafka.index-topic}",
            containerFactory = "knowledgeKafkaListenerContainerFactory")
    public void consumeIndex(ConsumerRecord<String, String> record) {
        consume(record, KnowledgeEventType.KNOWLEDGE_INDEX_REQUESTED);
    }

    private void consume(ConsumerRecord<String, String> record, KnowledgeEventType expected) {
        try {
            inboxService.accept(record.value(), expected);
        } catch (KnowledgeParseException exception) {
            // Invalid envelopes are poison messages. Publish the raw envelope to the configured
            // DLT before returning, so RECORD ack can safely advance this partition.
            try {
                kafkaTemplate.send(properties.getKnowledge().getKafka().getDeadLetterTopic(),
                        record.key(), record.value()).get();
            } catch (Exception publishFailure) {
                throw new IllegalStateException("Cannot publish malformed knowledge event to DLT", publishFailure);
            }
        }
    }
}
