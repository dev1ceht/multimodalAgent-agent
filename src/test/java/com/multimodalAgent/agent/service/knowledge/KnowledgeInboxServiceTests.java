package com.multimodalAgent.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeInboxEvent;
import com.multimodalAgent.agent.repository.KnowledgeInboxEventRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeInboxServiceTests {

    private final KnowledgeInboxEventRepository repository = mock(KnowledgeInboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KnowledgeInboxService service = new KnowledgeInboxService(repository, objectMapper);

    @Test
    void duplicateEventIsAcceptedWithoutCreatingAnotherInboxRow() throws Exception {
        String payload = payload("event-1", "request-1");
        when(repository.findById("event-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(KnowledgeInboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeInboxService.Acceptance first = service.accept(
                payload, KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED);
        when(repository.findById("event-1")).thenReturn(Optional.of(first.inbox()));

        KnowledgeInboxService.Acceptance duplicate = service.accept(
                payload, KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED);

        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.inbox().getEventId()).isEqualTo("event-1");
        verify(repository).saveAndFlush(any(KnowledgeInboxEvent.class));
    }

    @Test
    void reusingAnEventIdWithDifferentPayloadIsRejected() throws Exception {
        String original = payload("event-2", "request-1");
        when(repository.findById("event-2")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(KnowledgeInboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeInboxService.Acceptance first = service.accept(
                original, KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED);
        when(repository.findById("event-2")).thenReturn(Optional.of(first.inbox()));

        assertThatThrownBy(() -> service.accept(
                payload("event-2", "request-2"), KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("EVENT_ID_REUSED");
    }

    @Test
    void eventMustMatchTheTopicContract() throws Exception {
        when(repository.findById("event-3")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(
                payload("event-3", "request-3"), KnowledgeEventType.KNOWLEDGE_INDEX_REQUESTED))
                .isInstanceOf(KnowledgeParseException.class)
                .extracting(exception -> ((KnowledgeParseException) exception).getCode())
                .isEqualTo("UNEXPECTED_EVENT");
    }

    private String payload(String eventId, String correlationId) throws Exception {
        return objectMapper.writeValueAsString(new KnowledgeEvent(
                eventId,
                1,
                KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED.name(),
                "upload-1",
                1,
                Instant.parse("2026-09-12T00:00:00Z"),
                correlationId));
    }
}
