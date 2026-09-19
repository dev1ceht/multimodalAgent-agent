package com.multimodalAgent.agent.service.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ConversationContextJob;
import com.multimodalAgent.agent.domain.ConversationContextSummary;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ConversationContextJobRepository;
import com.multimodalAgent.agent.repository.ConversationContextSummaryRepository;
import com.multimodalAgent.agent.service.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ContextSummaryWorkerTests {

    @Test
    void advancesOnlyThroughTheTokenFittedSourceBatch() {
        Fixture fixture = fixture(ContextJobStatus.PENDING);
        List<ChatMessage> source = List.of(message(1L), message(2L), message(3L));
        when(fixture.messages.findBySession_IdAndUser_IdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Pageable.class))).thenReturn(source);
        when(fixture.compiler.fitSourceBatch("{}", source)).thenReturn(source.subList(0, 2));
        when(fixture.compiler.compile("{}", source.subList(0, 2)))
                .thenReturn(new ContextSummaryCompiler.CompiledSummary("{\"ok\":true}", 12));

        fixture.worker.pollDueJobs();

        ArgumentCaptor<ConversationContextSummary> saved =
                ArgumentCaptor.forClass(ConversationContextSummary.class);
        verify(fixture.summaries).save(saved.capture());
        assertThat(saved.getValue().getCoveredThroughMessageId()).isEqualTo(2L);
        assertThat(fixture.job.getStatus()).isEqualTo(ContextJobStatus.PENDING);
        assertThat(fixture.job.getAttempts()).isZero();
        assertThat(fixture.registry.get("multimodalagent.context.summary")
                .tags("outcome", "succeeded", "reason", "none")
                .timer().count()).isOne();
    }

    @Test
    void keepsTheOldWatermarkAndSchedulesRetryWhenCompilationFails() {
        Fixture fixture = fixture(ContextJobStatus.PENDING);
        ConversationContextSummary existing = summary(4L, 2L);
        when(fixture.summaries.findBySessionId(10L)).thenReturn(Optional.of(existing));
        when(fixture.summaries.findBySessionIdForUpdate(10L)).thenReturn(Optional.of(existing));
        List<ChatMessage> source = List.of(message(3L), message(4L));
        when(fixture.messages.findBySession_IdAndUser_IdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Pageable.class))).thenReturn(source);
        when(fixture.compiler.fitSourceBatch(existing.getSummaryJson(), source)).thenReturn(source);
        when(fixture.compiler.compile(existing.getSummaryJson(), source))
                .thenThrow(new IllegalStateException("model_timeout"));

        fixture.worker.pollDueJobs();

        assertThat(existing.getCoveredThroughMessageId()).isEqualTo(2L);
        assertThat(fixture.job.getStatus()).isEqualTo(ContextJobStatus.RETRY_WAIT);
        assertThat(fixture.job.getLeaseToken()).isNull();
        assertThat(fixture.job.getLastErrorCode()).isEqualTo("model_timeout");
        verify(fixture.summaries, never()).save(any());
        assertThat(fixture.registry.get("multimodalagent.context.summary")
                .tags("outcome", "retry_wait", "reason", "timeout")
                .timer().count()).isOne();
    }

    @Test
    void reclaimsAnExpiredProcessingLease() {
        Fixture fixture = fixture(ContextJobStatus.PROCESSING);
        fixture.job.setAttempts(1);
        fixture.job.setLeaseToken("expired");
        fixture.job.setLeaseUntil(Instant.now().minusSeconds(30));
        List<ChatMessage> source = List.of(message(1L), message(2L));
        when(fixture.messages.findBySession_IdAndUser_IdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Pageable.class))).thenReturn(source);
        when(fixture.compiler.fitSourceBatch("{}", source)).thenReturn(source);
        when(fixture.compiler.compile("{}", source))
                .thenReturn(new ContextSummaryCompiler.CompiledSummary("{\"ok\":true}", 12));

        fixture.worker.pollDueJobs();

        assertThat(fixture.job.getAttempts()).isZero();
        assertThat(fixture.job.getLeaseToken()).isNull();
        verify(fixture.summaries).save(any(ConversationContextSummary.class));
    }

    @Test
    void discardsAStaleResultWhenTheSummaryVersionChangesAfterClaim() {
        Fixture fixture = fixture(ContextJobStatus.PENDING);
        ConversationContextSummary claimed = summary(1L, 0L);
        ConversationContextSummary concurrent = summary(2L, 2L);
        when(fixture.summaries.findBySessionId(10L)).thenReturn(Optional.of(claimed));
        when(fixture.summaries.findBySessionIdForUpdate(10L))
                .thenReturn(Optional.of(claimed), Optional.of(concurrent));
        List<ChatMessage> source = List.of(message(1L), message(2L));
        when(fixture.messages.findBySession_IdAndUser_IdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Pageable.class))).thenReturn(source);
        when(fixture.compiler.fitSourceBatch("{}", source)).thenReturn(source);
        when(fixture.compiler.compile("{}", source))
                .thenReturn(new ContextSummaryCompiler.CompiledSummary("{\"ok\":true}", 12));

        fixture.worker.pollDueJobs();

        assertThat(concurrent.getVersion()).isEqualTo(2L);
        assertThat(concurrent.getCoveredThroughMessageId()).isEqualTo(2L);
        assertThat(fixture.job.getStatus()).isEqualTo(ContextJobStatus.PENDING);
        assertThat(fixture.job.getAttempts()).isZero();
        verify(fixture.summaries, never()).save(any());
        assertThat(fixture.registry.get("multimodalagent.context.summary.cas.conflicts")
                .counter().count()).isOne();
    }

    private Fixture fixture(ContextJobStatus initialStatus) {
        ConversationContextJobRepository jobs = mock(ConversationContextJobRepository.class);
        ConversationContextSummaryRepository summaries = mock(ConversationContextSummaryRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ContextSummaryCompiler compiler = mock(ContextSummaryCompiler.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        when(jobs.save(any(ConversationContextJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.saveAndFlush(any(ConversationContextJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(summaries.save(any(ConversationContextSummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConversationContextJob job = new ConversationContextJob();
        job.setSessionId(10L);
        job.setUserId(20L);
        job.setDesiredThroughMessageId(10L);
        job.setStatus(initialStatus);
        job.setNextAttemptAt(Instant.now().minusSeconds(5));
        job.setUpdatedAt(Instant.now().minusSeconds(5));
        when(jobs.findBySessionIdForUpdate(10L)).thenReturn(Optional.of(job));
        if (initialStatus == ContextJobStatus.PROCESSING) {
            when(jobs.findByStatusAndLeaseUntilLessThanEqualOrderByUpdatedAtAsc(
                    any(), any(), any(Pageable.class))).thenReturn(List.of(job));
        } else {
            when(jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
                    eq(initialStatus), any(), any(Pageable.class))).thenReturn(List.of(job));
        }
        when(summaries.findBySessionId(10L)).thenReturn(Optional.empty());
        when(summaries.findBySessionIdForUpdate(10L)).thenReturn(Optional.empty());
        when(messages.findBySession_IdAndUser_IdAndIdLessThanEqualOrderByIdDesc(
                anyLong(), anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(message(10L), message(9L), message(8L), message(7L)));

        multimodalAgentProperties properties = new multimodalAgentProperties();
        properties.getChat().setContextMode("summary");
        properties.getChat().setContextSummaryRecentMessages(2);
        properties.getChat().setContextSummaryTriggerMessages(3);
        properties.getChat().setContextSummaryBaseRetryDelaySeconds(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContextSummaryWorker worker = new ContextSummaryWorker(
                jobs,
                summaries,
                messages,
                compiler,
                properties,
                transactionManager,
                new OperationalMetrics(registry));
        return new Fixture(worker, jobs, summaries, messages, compiler, job, registry);
    }

    private ChatMessage message(Long id) {
        ChatMessage message = new ChatMessage();
        ReflectionTestUtils.setField(message, "id", id);
        message.setRole(MessageRole.USER);
        message.setContent("消息" + id);
        return message;
    }

    private ConversationContextSummary summary(long version, long watermark) {
        ConversationContextSummary summary = new ConversationContextSummary();
        summary.setSessionId(10L);
        summary.setUserId(20L);
        summary.setVersion(version);
        summary.setCoveredThroughMessageId(watermark);
        summary.setSummaryJson("{}");
        return summary;
    }

    private record Fixture(
            ContextSummaryWorker worker,
            ConversationContextJobRepository jobs,
            ConversationContextSummaryRepository summaries,
            ChatMessageRepository messages,
            ContextSummaryCompiler compiler,
            ConversationContextJob job,
            SimpleMeterRegistry registry
    ) {
    }
}
