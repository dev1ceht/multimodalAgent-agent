package com.multimodalAgent.agent.service.agentruntime.tools;

import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import java.util.List;

/** Application-owned, student-scoped projection used by both HTTP and Agent tools. */
public interface SupportStatusProvider {

    List<StudentSupportStatusResponse> statusFor(ConversationIdentity identity);
}
