package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ChatMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天消息的数据访问接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 按配置的历史窗口读取最近消息，避免 Redis 与数据库回退窗口不一致。 */
    List<ChatMessage> findBySession_IdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    /** 管理员点开记录时读取完整会话。 */
    List<ChatMessage> findBySession_PublicIdOrderByCreatedAtAsc(String publicId);
}
