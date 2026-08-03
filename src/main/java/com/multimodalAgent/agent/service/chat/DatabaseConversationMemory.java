package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 MySQL 长期记忆和 Redis 短期记忆的对话记忆适配器。
 */
@Service
public class DatabaseConversationMemory implements ConversationMemory {

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final multimodalAgentProperties properties;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;

    public DatabaseConversationMemory(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            multimodalAgentProperties properties,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.properties = properties;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
    }

    @Override
    @Transactional
    public ConversationIdentity open(Long userId, String sessionPublicId, String title) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = resolveSession(user, sessionPublicId, title);
        return identity(user, session);
    }

    @Override
    public List<AiMessage> recentModelHistory(ConversationIdentity identity) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(identity.sessionPublicId());
        if (!redisHistory.isEmpty()) {
            return window(redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList());
        }

        // Redis 中没有短期记忆时，从 MySQL 长期记忆恢复最近上下文。
        List<ChatMessage> databaseHistory = new ArrayList<>(
                chatMessageRepository.findTop20BySession_IdOrderByCreatedAtDesc(identity.sessionId()));
        Collections.reverse(databaseHistory);
        shortTermMemoryService.refresh(identity.sessionPublicId(), databaseHistory.stream()
                .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                .toList());
        return window(databaseHistory.stream()
                .map(this::toAiMessage)
                .toList());
    }

    @Override
    public List<AiMessage> withCurrentInput(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        return window(history);
    }

    @Override
    @Transactional
    public void append(ConversationIdentity identity, MessageRole role, String content) {
        UserAccount user = userAccountRepository.findById(identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = chatSessionRepository
                .findByPublicIdAndUser_Id(identity.sessionPublicId(), identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
        shortTermMemoryService.append(session.getPublicId(), role, content);
    }

    @Override
    @Transactional
    public void appendMultimodalMemory(ConversationIdentity identity, MultimodalAnalysis analysis) {
        append(identity, MessageRole.SYSTEM, multimodalMemory(analysis));
    }

    private List<AiMessage> window(List<AiMessage> messages) {
        int limit = messageWindowLimit();
        return messages.stream()
                .skip(Math.max(0, messages.size() - limit))
                .toList();
    }

    private int messageWindowLimit() {
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private ChatSession resolveSession(UserAccount user, String publicId, String title) {
        if (publicId != null && !publicId.isBlank()) {
            return chatSessionRepository.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setTitle(title.length() > 36 ? title.substring(0, 36) : title);
        return chatSessionRepository.save(session);
    }

    private ConversationIdentity identity(UserAccount user, ChatSession session) {
        return new ConversationIdentity(
                user.getId(),
                session.getId(),
                session.getPublicId(),
                user.getUsername());
    }

    private String multimodalMemory(MultimodalAnalysis analysis) {
        String modalities = String.join("、", analysis.signals().stream()
                .map(MultimodalSignal::modality)
                .distinct()
                .toList());
        String evidence = String.join("；", analysis.signals().stream()
                .map(signal -> signal.modality() + "=" + signal.evidence())
                .toList());
        return """
                【多模态分析记忆】
                用户本轮上传了%s，后端已完成多模态情绪分析。后续如果用户追问“你是否根据图片/语音/视频分析”，应说明：我是基于后端多模态分析结果和你的文字一起判断，不是只凭文字猜测。不要否认已上传附件，也不要声称自己直接查看了原始文件。
                分析摘要：%s
                情绪标签：%s
                分析证据：%s
                """.formatted(
                modalities.isBlank() ? "附件" : modalities,
                analysis.summary(),
                analysis.emotionTagsJson(),
                evidence.isBlank() ? "无" : evidence);
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> PromptTemplates.multimodalContext(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> PromptTemplates.multimodalContext(content);
            case USER -> AiMessage.user(content);
        };
    }
}
