package com.multimodalAgent.agent.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.config.MindCareAgentProperties;
import com.multimodalAgent.agent.service.agentruntime.AgentSessionLeaseService;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentSessionIsolationTests {

    @Test
    void permitsOnlyOneRunPerTrustedUserAndSessionAndReleasesOnlyItsOwnLease() {
        MindCareAgentProperties properties = new MindCareAgentProperties();
        AgentSessionLeaseService leases = new AgentSessionLeaseService(properties);
        ConversationIdentity firstStudent = new ConversationIdentity(7L, 11L, "s-1", "student");
        ConversationIdentity secondStudent = new ConversationIdentity(8L, 11L, "s-1", "student-2");
        Instant deadline = Instant.now().plusSeconds(30);

        assertThat(leases.tryAcquire(firstStudent, "run-1", deadline)).isTrue();
        assertThat(leases.tryAcquire(firstStudent, "run-2", deadline)).isFalse();
        assertThat(leases.tryAcquire(secondStudent, "run-2", deadline)).isTrue();

        leases.release(firstStudent, "run-2");
        assertThat(leases.tryAcquire(firstStudent, "run-3", deadline)).isFalse();
        leases.release(firstStudent, "run-1");
        assertThat(leases.tryAcquire(firstStudent, "run-3", deadline)).isTrue();
    }

    @Test
    void expiredLeaseIsRecoverableAfterAClientOrProcessFailure() {
        AgentSessionLeaseService leases = new AgentSessionLeaseService(new MindCareAgentProperties());
        ConversationIdentity identity = new ConversationIdentity(7L, 11L, "s-1", "student");

        assertThat(leases.tryAcquire(identity, "run-1", Instant.now().minusSeconds(1))).isTrue();
        assertThat(leases.tryAcquire(identity, "run-2", Instant.now().plusSeconds(30))).isTrue();
    }
}
