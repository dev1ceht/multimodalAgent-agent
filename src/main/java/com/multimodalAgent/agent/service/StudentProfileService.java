package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.StudentProfileUpdateRequest;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudentProfileService {

    private final StudentProfileRepository studentProfileRepository;

    public StudentProfileService(StudentProfileRepository studentProfileRepository) {
        this.studentProfileRepository = studentProfileRepository;
    }

    @Transactional(readOnly = true)
    public StudentProfile get(CurrentUser viewer) {
        requireStudent(viewer);
        return studentProfileRepository.findByUser_Id(viewer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student profile not found"));
    }

    @Transactional
    public StudentProfile update(CurrentUser viewer, StudentProfileUpdateRequest request) {
        StudentProfile profile = get(viewer);
        if (request.gender() != null) {
            profile.setGender(request.gender().trim());
        }
        if (request.phone() != null) {
            profile.setPhone(PrivacyMasker.phone(request.phone()));
        }
        if (request.emergencyContactMasked() != null) {
            profile.setEmergencyContactMasked(PrivacyMasker.emergencyContact(request.emergencyContactMasked()));
        }
        return studentProfileRepository.save(profile);
    }

    private void requireStudent(CurrentUser viewer) {
        if (viewer == null || viewer.getId() == null || viewer.getAuthorities() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student profile access denied");
        }
        Set<String> roles = viewer.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        if (!UserRole.isStudentAccount(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student profile access denied");
        }
    }
}
