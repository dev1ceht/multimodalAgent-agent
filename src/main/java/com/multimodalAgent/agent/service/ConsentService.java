package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.ConsentRecord;
import com.multimodalAgent.agent.domain.ConsentStatus;
import com.multimodalAgent.agent.domain.ConsentType;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.ConsentGrantRequest;
import com.multimodalAgent.agent.repository.ConsentRecordRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConsentService {

    private final ConsentRecordRepository consentRecordRepository;
    private final UserAccountRepository userAccountRepository;

    public ConsentService(
            ConsentRecordRepository consentRecordRepository,
            UserAccountRepository userAccountRepository
    ) {
        this.consentRecordRepository = consentRecordRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional(readOnly = true)
    public List<ConsentRecord> list(CurrentUser viewer) {
        requireStudent(viewer);
        return consentRecordRepository.findByStudentUser_IdOrderByCreatedAtDesc(viewer.getId());
    }

    @Transactional
    public ConsentRecord grant(CurrentUser viewer, ConsentGrantRequest request) {
        requireStudent(viewer);
        String version = request.version().trim();
        return consentRecordRepository
                .findTopByStudentUser_IdAndConsentTypeAndVersionAndStatusOrderByCreatedAtDesc(
                        viewer.getId(),
                        request.consentType(),
                        version,
                        ConsentStatus.GRANTED)
                .orElseGet(() -> grantNewVersion(viewer, request, version));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveConsent(CurrentUser viewer, ConsentType consentType) {
        if (!isStudent(viewer) || consentType == null) {
            return false;
        }
        return consentRecordRepository.existsByStudentUser_IdAndConsentTypeAndStatus(
                viewer.getId(), consentType, ConsentStatus.GRANTED);
    }

    /** Requires the legal and processing consents needed before a chat request reaches the model. */
    @Transactional(readOnly = true)
    public void requireChatConsent(CurrentUser viewer) {
        requireStudent(viewer);
        if (!hasActiveConsent(viewer, ConsentType.PRIVACY_NOTICE)
                || !hasActiveConsent(
                        viewer,
                        ConsentType.SENSITIVE_DATA_PROCESSING)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Required consent has not been granted");
        }
    }

    private ConsentRecord grantNewVersion(CurrentUser viewer, ConsentGrantRequest request, String version) {
        Instant now = Instant.now();
        List<ConsentRecord> activeRecords = consentRecordRepository
                .findByStudentUser_IdAndConsentTypeAndStatus(
                        viewer.getId(),
                        request.consentType(),
                        ConsentStatus.GRANTED);
        activeRecords.forEach(record -> record.revoke(now));
        if (!activeRecords.isEmpty()) {
            consentRecordRepository.saveAll(activeRecords);
        }

        UserAccount student = userAccountRepository.findById(viewer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student account not found"));
        ConsentRecord record = new ConsentRecord();
        record.setStudentUser(student);
        record.setConsentType(request.consentType());
        record.setVersion(version);
        record.setStatus(ConsentStatus.GRANTED);
        record.setGrantedAt(now);
        return consentRecordRepository.save(record);
    }

    private void requireStudent(CurrentUser viewer) {
        if (!isStudent(viewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student consent access denied");
        }
    }

    private boolean isStudent(CurrentUser viewer) {
        if (viewer == null || viewer.getId() == null || viewer.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = viewer.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        return UserRole.isStudentAccount(roles);
    }
}
