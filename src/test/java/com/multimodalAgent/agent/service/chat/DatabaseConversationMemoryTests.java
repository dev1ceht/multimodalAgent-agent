package com.multimodalAgent.agent.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService;
import com.multimodalAgent.agent.service.memory.LongTermMemoryWriter;
import com.multimodalAgent.agent.service.memory.MemoryInput;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class DatabaseConversationMemoryTests {

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
    private final LongTermMemoryWriter longTermMemoryWriter = mock(LongTermMemoryWriter.class);
    private final multimodalAgentProperties properties = new multimodalAgentProperties();
    private final DatabaseConversationMemory memory = new DatabaseConversationMemory(
            userAccountRepository,
            chatSessionRepository,
            chatMessageRepository,
            properties,
            new PrivacySanitizer(),
            shortTermMemoryService,
            longTermMemoryWriter);

    private final ConversationIdentity identity =
            new ConversationIdentity(7L, 11L, "session-1", "student");

    @BeforeEach
    void setUp() {
        properties.getChat().setHistoryLimit(1);
    }

    @Test
    void redisHistoryIsWindowedAndCurrentInputUsesTheSameWindow() {
        when(shortTermMemoryService.recent("session-1")).thenReturn(List.of(
                new MemoryMessage(MessageRole.USER, "旧问题"),
                new MemoryMessage(MessageRole.ASSISTANT, "旧回答"),
                new MemoryMessage(MessageRole.USER, "最近问题"),
                new MemoryMessage(MessageRole.ASSISTANT, "最近回答")));

        ConversationHistory history = memory.recentHistory(identity);
        ConversationHistory withCurrentInput =
                memory.appendCurrentInputWithinWindow(history, "新问题");

        assertThat(history.messages()).extracting(ConversationMessage::content)
                .containsExactly("最近问题", "最近回答");
        assertThat(withCurrentInput.messages()).extracting(ConversationMessage::content)
                .containsExactly("最近回答", "新问题");
    }

    @Test
    void savedUserMessageIsEnqueuedForLongTermMemoryCompilation() {
        UserAccount user = new UserAccount();
        ReflectionTestUtils.setField(user, "id", 7L);
        ChatSession session = new ChatSession();
        ReflectionTestUtils.setField(session, "id", 11L);
        session.setPublicId("session-1");
        session.setUser(user);
        when(userAccountRepository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(chatSessionRepository.findByPublicIdAndUser_Id("session-1", 7L))
                .thenReturn(java.util.Optional.of(session));
        when(chatMessageRepository.save(org.mockito.ArgumentMatchers.any(ChatMessage.class)))
                .thenAnswer(invocation -> {
                    ChatMessage saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 101L);
                    return saved;
                });

        memory.append(identity, MessageRole.USER, "我周五参加答辩");

        ArgumentCaptor<MemoryInput> input = ArgumentCaptor.forClass(MemoryInput.class);
        verify(longTermMemoryWriter).enqueue(input.capture());
        assertThat(input.getValue().userId()).isEqualTo(7L);
        assertThat(input.getValue().sessionId()).isEqualTo(11L);
        assertThat(input.getValue().messageId()).isEqualTo(101L);
        assertThat(input.getValue().content()).isEqualTo("我周五参加答辩");
    }

    @Test
    void databaseFallbackRefreshesRedisAndAppliesTheSameWindow() {
        when(shortTermMemoryService.recent("session-1")).thenReturn(List.of());
        ChatMessage older = message(MessageRole.USER, "旧问题");
        ChatMessage newer = message(MessageRole.ASSISTANT, "最近回答");
        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(newer, older));

        ConversationHistory history = memory.recentHistory(identity);

        assertThat(history.messages()).extracting(ConversationMessage::content)
                .containsExactly("旧问题", "最近回答");
        verify(shortTermMemoryService).refresh(
                "session-1",
                List.of(
                        new MemoryMessage(MessageRole.USER, "旧问题"),
                        new MemoryMessage(MessageRole.ASSISTANT, "最近回答")));
    }

    @Test
    void databaseFallbackUsesConfiguredWindowInsteadOfFixedTwentyMessages() {
        properties.getChat().setHistoryLimit(11);
        when(shortTermMemoryService.recent("session-1")).thenReturn(List.of());
        when(chatMessageRepository.findBySession_IdOrderByCreatedAtDesc(
                eq(11L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        memory.recentHistory(identity);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatMessageRepository).findBySession_IdOrderByCreatedAtDesc(
                eq(11L),
                pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(22);
    }

    private ChatMessage message(MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
