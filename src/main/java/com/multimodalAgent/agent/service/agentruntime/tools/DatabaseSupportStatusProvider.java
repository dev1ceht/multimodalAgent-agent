package com.multimodalAgent.agent.service.agentruntime.tools;

import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.RiskCaseService;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reuses the existing authorized self-view application method; no staff projection is exposed. */
@Service
public final class DatabaseSupportStatusProvider implements SupportStatusProvider {

    private final RiskCaseService riskCaseService;
    private final UserAccountRepository userAccountRepository;

    public DatabaseSupportStatusProvider(
            RiskCaseService riskCaseService,
            UserAccountRepository userAccountRepository
    ) {
        this.riskCaseService = riskCaseService;
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public List<StudentSupportStatusResponse> statusFor(ConversationIdentity identity) {
        if (identity == null || identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Student identity is required");
        }
        UserAccount account = userAccountRepository.findById(identity.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Student identity is invalid"));
        return riskCaseService.studentSupportStatus(new CurrentUser(account));
    }
}
