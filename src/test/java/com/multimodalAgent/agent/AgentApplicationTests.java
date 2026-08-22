package com.multimodalAgent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:multimodalAgent-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=mock",
        "multimodal-agent.knowledge.index-sync.enabled=false"
})
@ActiveProfiles("test")
class AgentApplicationTests {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void defaultLocalApplicationContextSeedsDemoAccounts() {
        assertThat(userAccountRepository.count()).isEqualTo(3L);
        assertThat(userAccountRepository.findByUsername("admin")).isPresent();
        assertThat(userAccountRepository.findByUsername("schooladmin")).isPresent();
        assertThat(userAccountRepository.findByUsername("student")).isPresent();
    }
}
