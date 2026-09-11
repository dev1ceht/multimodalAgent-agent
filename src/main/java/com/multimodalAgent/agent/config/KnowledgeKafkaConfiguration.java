package com.multimodalAgent.agent.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.config.NewTopic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Kafka beans are deliberately absent from legacy/local/test mode. */
@Configuration
@EnableKafka
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeKafkaConfiguration {

    private final multimodalAgentProperties properties;

    public KnowledgeKafkaConfiguration(multimodalAgentProperties properties) {
        this.properties = properties;
        var kafka = properties.getKnowledge().getKafka();
        require(kafka.getBootstrapServers(), "KAFKA_BOOTSTRAP_SERVERS");
        require(kafka.getParseTopic(), "KNOWLEDGE_PARSE_TOPIC");
        require(kafka.getIndexTopic(), "KNOWLEDGE_INDEX_TOPIC");
        require(kafka.getDeadLetterTopic(), "KNOWLEDGE_DLT_TOPIC");
    }

    @Bean
    public KafkaAdmin knowledgeKafkaAdmin() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getKnowledge().getKafka().getBootstrapServers());
        return new KafkaAdmin(config);
    }

    @Bean
    public NewTopic knowledgeParseTopic() {
        return topic(properties.getKnowledge().getKafka().getParseTopic());
    }

    @Bean
    public NewTopic knowledgeIndexTopic() {
        return topic(properties.getKnowledge().getKafka().getIndexTopic());
    }

    @Bean
    public NewTopic knowledgeDeadLetterTopic() {
        return topic(properties.getKnowledge().getKafka().getDeadLetterTopic());
    }

    @Bean
    public ProducerFactory<String, String> knowledgeProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getKnowledge().getKafka().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, properties.getKnowledge().getKafka().getClientId());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> knowledgeKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, String> knowledgeConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getKnowledge().getKafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getKnowledge().getKafka().getConsumerGroup());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, properties.getKnowledge().getKafka().getClientId() + "-consumer");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean(name = "knowledgeKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> knowledgeKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, properties.getKnowledge().getKafka().getConsumerConcurrency()));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setKafkaAwareTransactionManager(null);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    @Bean(name = "knowledgeWorkerExecutor")
    public ThreadPoolTaskExecutor knowledgeWorkerExecutor() {
        int concurrency = Math.max(1, properties.getKnowledge().getKafka().getWorkerConcurrency());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(1, concurrency * 2));
        executor.setThreadNamePrefix("knowledge-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(Math.max(1, properties.getKnowledge().getKafka().getPartitions()))
                .replicas(Math.max(1, properties.getKnowledge().getKafka().getReplicationFactor()))
                .build();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when kafka-minio ingestion is enabled");
        }
    }
}
