package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.StudentStatus;
import com.multimodalAgent.agent.service.PrivacyMasker;
import java.time.Instant;

public record StudentProfileResponse(
        Long id,
        String studentNo,
        String departmentCode,
        String departmentName,
        String majorCode,
        String majorName,
        String classCode,
        String className,
        Integer gradeYear,
        String gender,
        String phoneMasked,
        String emergencyContactMasked,
        StudentStatus status,
        Instant updatedAt
) {
    public static StudentProfileResponse from(StudentProfile profile) {
        return new StudentProfileResponse(
                profile.getId(),
                profile.getStudentNo(),
                profile.getDepartment().getCode(),
                profile.getDepartment().getName(),
                profile.getMajor().getCode(),
                profile.getMajor().getName(),
                profile.getStudentClass().getCode(),
                profile.getStudentClass().getName(),
                profile.getGradeYear(),
                profile.getGender(),
                PrivacyMasker.phone(profile.getPhone()),
                PrivacyMasker.emergencyContact(profile.getEmergencyContactMasked()),
                profile.getStatus(),
                profile.getUpdatedAt());
    }

}
