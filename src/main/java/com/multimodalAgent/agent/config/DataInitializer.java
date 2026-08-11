package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.knowledge.KnowledgeIngestionService;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class DataInitializer implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final multimodalAgentProperties properties;

    public DataInitializer(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            KnowledgeIngestionService knowledgeIngestionService,
            multimodalAgentProperties properties
    ) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 首次启动准备演示账号和内置知识库；已有数据时不会覆盖。
        if (properties.getSecurity().isDemoAccountsEnabled()) {
            seedUsers();
        }
        knowledgeIngestionService.ingestClasspathKnowledgeIfEmpty();
    }

    private void seedUsers() {
        seedUser(
                "admin",
                "Counselor Admin",
                "admin123",
                Set.of(UserRole.SYSTEM_ADMIN.authority(), UserRole.COUNSELOR.authority()));
        seedUser(
                "schooladmin",
                "School Operations Admin",
                "schooladmin123",
                Set.of(UserRole.SCHOOL_ADMIN.authority()));
        seedUser("student", "student", "student123", Set.of(UserRole.STUDENT.authority()));
    }

    private void seedUser(String username, String displayName, String password, Set<String> roles) {
        if (userAccountRepository.findByUsername(username).isPresent()) {
            return;
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(displayName);
        account.setPassword(passwordEncoder.encode(password));
        account.setRoles(roles);
        userAccountRepository.save(account);
    }
}
