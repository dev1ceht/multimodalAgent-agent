package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.knowledge.KnowledgeIngestionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DataInitializerTests {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private KnowledgeIngestionService knowledgeIngestionService;

    @Mock
    private multimodalAgentProperties properties;

    @Mock
    private multimodalAgentProperties.Security security;

    @InjectMocks
    private DataInitializer dataInitializer;

    @Test
    void demoAccountsAreDisabledByDefault() {
        assertThat(new multimodalAgentProperties().getSecurity().isDemoAccountsEnabled()).isFalse();
    }

    @Test
    void doesNotCreateDemoAccountsWhenExplicitlyDisabled() {
        when(properties.getSecurity()).thenReturn(security);
        when(security.isDemoAccountsEnabled()).thenReturn(false);

        dataInitializer.run(new DefaultApplicationArguments());

        verify(userAccountRepository, never()).count();
        verify(userAccountRepository, never()).save(any(UserAccount.class));
        verify(knowledgeIngestionService).ingestClasspathKnowledgeIfEmpty();
    }

    @Test
    void createsKnownDemoAccountsOnlyWhenExplicitlyEnabledAndDatabaseIsEmpty() {
        when(properties.getSecurity()).thenReturn(security);
        when(security.isDemoAccountsEnabled()).thenReturn(true);
        when(userAccountRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("admin123")).thenReturn("encoded-admin");
        when(passwordEncoder.encode("student123")).thenReturn("encoded-student");

        dataInitializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<UserAccount> accounts = captor.getAllValues();

        UserAccount admin = accounts.stream()
                .filter(account -> account.getUsername().equals("admin"))
                .findFirst()
                .orElseThrow();
        assertThat(admin.getPassword()).isEqualTo("encoded-admin");
        assertThat(admin.getRoles()).isEqualTo(Set.of(
                UserRole.SYSTEM_ADMIN.authority(),
                UserRole.COUNSELOR.authority()));

        UserAccount student = accounts.stream()
                .filter(account -> account.getUsername().equals("student"))
                .findFirst()
                .orElseThrow();
        assertThat(student.getPassword()).isEqualTo("encoded-student");
        assertThat(student.getRoles()).isEqualTo(Set.of(UserRole.STUDENT.authority()));
    }

}
