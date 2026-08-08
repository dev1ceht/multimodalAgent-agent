package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "counselor_assignments")
public class CounselorAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counselor_user_id", nullable = false)
    private UserAccount counselor;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private AssignmentScopeType scopeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "major_id")
    private Major major;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private ClassGroup studentClass;

    @Column(name = "grade_year")
    private Integer gradeYear;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public UserAccount getCounselor() {
        return counselor;
    }

    public void setCounselor(UserAccount counselor) {
        this.counselor = counselor;
    }

    public AssignmentScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AssignmentScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public Major getMajor() {
        return major;
    }

    public void setMajor(Major major) {
        this.major = major;
    }

    public ClassGroup getStudentClass() {
        return studentClass;
    }

    public void setStudentClass(ClassGroup studentClass) {
        this.studentClass = studentClass;
    }

    public Integer getGradeYear() {
        return gradeYear;
    }

    public void setGradeYear(Integer gradeYear) {
        this.gradeYear = gradeYear;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    @PreUpdate
    private void validateScopeBinding() {
        if (scopeType == null || configuredScopeCount() != 1 || !hasExpectedScopeBinding()) {
            throw new IllegalStateException("Counselor assignment must bind exactly one matching scope");
        }
    }

    public boolean covers(StudentProfile profile) {
        if (!enabled || profile == null || scopeType == null) {
            return false;
        }
        return switch (scopeType) {
            case DEPARTMENT -> sameEntity(department, profile.getDepartment());
            case MAJOR -> sameEntity(major, profile.getMajor());
            case CLASS -> sameEntity(studentClass, profile.getStudentClass());
            case GRADE -> gradeYear != null && gradeYear.equals(profile.getGradeYear());
        };
    }

    private static boolean sameEntity(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        Long leftId = entityId(left);
        Long rightId = entityId(right);
        return leftId != null && leftId.equals(rightId);
    }

    private static Long entityId(Object entity) {
        if (entity instanceof Department department) {
            return department.getId();
        }
        if (entity instanceof Major major) {
            return major.getId();
        }
        if (entity instanceof ClassGroup classGroup) {
            return classGroup.getId();
        }
        return null;
    }

    private int configuredScopeCount() {
        int count = 0;
        if (department != null) {
            count++;
        }
        if (major != null) {
            count++;
        }
        if (studentClass != null) {
            count++;
        }
        if (gradeYear != null) {
            count++;
        }
        return count;
    }

    private boolean hasExpectedScopeBinding() {
        return switch (scopeType) {
            case DEPARTMENT -> department != null;
            case MAJOR -> major != null;
            case CLASS -> studentClass != null;
            case GRADE -> gradeYear != null;
        };
    }
}
