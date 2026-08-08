package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.domain.AssignmentScopeType;
import com.multimodalAgent.agent.domain.ClassGroup;
import com.multimodalAgent.agent.domain.CounselorAssignment;
import com.multimodalAgent.agent.domain.Department;
import com.multimodalAgent.agent.domain.Major;
import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.StudentStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ClassGroupRepository;
import com.multimodalAgent.agent.repository.CounselorAssignmentRepository;
import com.multimodalAgent.agent.repository.DepartmentRepository;
import com.multimodalAgent.agent.repository.MajorRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@ConditionalOnProperty(
        prefix = "multimodal-agent.security",
        name = "demo-accounts-enabled",
        havingValue = "true")
public class DemoScopeInitializer implements ApplicationRunner {

    private static final String DEMO_DEPARTMENT_CODE = "DEMO";
    private static final String DEMO_MAJOR_CODE = "DEMO-GENERAL";
    private static final String DEMO_CLASS_CODE = "DEMO-CLASS";
    private static final String DEMO_STUDENT_NO = "DEMO-0001";

    private final UserAccountRepository userAccountRepository;
    private final DepartmentRepository departmentRepository;
    private final MajorRepository majorRepository;
    private final ClassGroupRepository classGroupRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final CounselorAssignmentRepository counselorAssignmentRepository;

    public DemoScopeInitializer(
            UserAccountRepository userAccountRepository,
            DepartmentRepository departmentRepository,
            MajorRepository majorRepository,
            ClassGroupRepository classGroupRepository,
            StudentProfileRepository studentProfileRepository,
            CounselorAssignmentRepository counselorAssignmentRepository
    ) {
        this.userAccountRepository = userAccountRepository;
        this.departmentRepository = departmentRepository;
        this.majorRepository = majorRepository;
        this.classGroupRepository = classGroupRepository;
        this.studentProfileRepository = studentProfileRepository;
        this.counselorAssignmentRepository = counselorAssignmentRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserAccount admin = userAccountRepository.findByUsername("admin").orElse(null);
        UserAccount student = userAccountRepository.findByUsername("student").orElse(null);
        if (admin == null || student == null || student.getId() == null || admin.getId() == null) {
            return;
        }

        Department department = departmentRepository.findByCode(DEMO_DEPARTMENT_CODE)
                .orElseGet(() -> saveDepartment());
        Major major = majorRepository.findByDepartment_IdAndCode(department.getId(), DEMO_MAJOR_CODE)
                .orElseGet(() -> saveMajor(department));
        ClassGroup classGroup = classGroupRepository.findByMajor_IdAndCode(major.getId(), DEMO_CLASS_CODE)
                .orElseGet(() -> saveClassGroup(major));

        StudentProfile profile = studentProfileRepository.findByUser_Id(student.getId())
                .orElseGet(() -> saveProfile(student, department, major, classGroup));
        ensureAssignment(admin, profile, classGroup);
    }

    private Department saveDepartment() {
        Department department = new Department();
        department.setCode(DEMO_DEPARTMENT_CODE);
        department.setName("Demo Department");
        return departmentRepository.save(department);
    }

    private Major saveMajor(Department department) {
        Major major = new Major();
        major.setDepartment(department);
        major.setCode(DEMO_MAJOR_CODE);
        major.setName("Demo Major");
        return majorRepository.save(major);
    }

    private ClassGroup saveClassGroup(Major major) {
        ClassGroup classGroup = new ClassGroup();
        classGroup.setMajor(major);
        classGroup.setCode(DEMO_CLASS_CODE);
        classGroup.setName("Demo Class");
        classGroup.setGradeYear(2025);
        return classGroupRepository.save(classGroup);
    }

    private StudentProfile saveProfile(
            UserAccount student,
            Department department,
            Major major,
            ClassGroup classGroup
    ) {
        StudentProfile profile = new StudentProfile();
        profile.setUser(student);
        profile.setStudentNo(DEMO_STUDENT_NO);
        profile.setDepartment(department);
        profile.setMajor(major);
        profile.setStudentClass(classGroup);
        profile.setGradeYear(classGroup.getGradeYear());
        profile.setStatus(StudentStatus.ACTIVE);
        return studentProfileRepository.save(profile);
    }

    private void ensureAssignment(UserAccount admin, StudentProfile profile, ClassGroup classGroup) {
        List<CounselorAssignment> assignments = counselorAssignmentRepository
                .findByCounselor_IdAndEnabledTrue(admin.getId());
        boolean assigned = assignments.stream().anyMatch(assignment -> assignment.covers(profile));
        if (assigned) {
            return;
        }
        CounselorAssignment assignment = new CounselorAssignment();
        assignment.setCounselor(admin);
        assignment.setScopeType(AssignmentScopeType.CLASS);
        assignment.setStudentClass(classGroup);
        counselorAssignmentRepository.save(assignment);
    }
}
