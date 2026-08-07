package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
