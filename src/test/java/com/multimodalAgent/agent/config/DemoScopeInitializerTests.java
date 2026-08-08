package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.multimodalAgent.agent.domain.AssignmentScopeType;
import com.multimodalAgent.agent.domain.ClassGroup;
import com.multimodalAgent.agent.domain.CounselorAssignment;
import com.multimodalAgent.agent.domain.Department;
import com.multimodalAgent.agent.domain.Major;
import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ClassGroupRepository;
import com.multimodalAgent.agent.repository.CounselorAssignmentRepository;
import com.multimodalAgent.agent.repository.DepartmentRepository;
import com.multimodalAgent.agent.repository.MajorRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class DemoScopeInitializerTests {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private MajorRepository majorRepository;

    @Mock
    private ClassGroupRepository classGroupRepository;

    @Mock
    private StudentProfileRepository studentProfileRepository;

    @Mock
    private CounselorAssignmentRepository counselorAssignmentRepository;

    @Test
    void createsAnIdempotentDemoStudentScopeForTheDemoOperator() {
        UserAccount admin = account(1L);
        UserAccount student = account(2L);
        Department department = department(10L);
        Major major = major(20L);
        ClassGroup classGroup = classGroup();
        StudentProfile profile = org.mockito.Mockito.mock(StudentProfile.class);
        CounselorAssignment existingAssignment = org.mockito.Mockito.mock(CounselorAssignment.class);

        when(userAccountRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userAccountRepository.findByUsername("student")).thenReturn(Optional.of(student));
        when(departmentRepository.findByCode("DEMO")).thenReturn(Optional.of(department));
        when(majorRepository.findByDepartment_IdAndCode(10L, "DEMO-GENERAL"))
                .thenReturn(Optional.of(major));
        when(classGroupRepository.findByMajor_IdAndCode(20L, "DEMO-CLASS"))
                .thenReturn(Optional.of(classGroup));
        when(studentProfileRepository.findByUser_Id(2L))
                .thenReturn(Optional.empty(), Optional.of(profile));
        when(studentProfileRepository.save(any(StudentProfile.class))).thenReturn(profile);
        when(counselorAssignmentRepository.findByCounselor_IdAndEnabledTrue(1L))
                .thenReturn(List.of(), List.of(existingAssignment));
        when(existingAssignment.covers(profile)).thenReturn(true);

        initializer().run(new DefaultApplicationArguments());
        initializer().run(new DefaultApplicationArguments());

        ArgumentCaptor<CounselorAssignment> captor = ArgumentCaptor.forClass(CounselorAssignment.class);
        verify(studentProfileRepository).save(any(StudentProfile.class));
        verify(counselorAssignmentRepository).save(captor.capture());
        CounselorAssignment assignment = captor.getValue();
        assertThat(assignment.getCounselor()).isSameAs(admin);
        assertThat(assignment.getScopeType()).isEqualTo(AssignmentScopeType.CLASS);
        assertThat(assignment.getStudentClass()).isSameAs(classGroup);
    }

    private DemoScopeInitializer initializer() {
        return new DemoScopeInitializer(
                userAccountRepository,
                departmentRepository,
                majorRepository,
                classGroupRepository,
                studentProfileRepository,
                counselorAssignmentRepository);
    }

    private UserAccount account(Long id) {
        UserAccount account = org.mockito.Mockito.mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    private Department department(Long id) {
        Department department = org.mockito.Mockito.mock(Department.class);
        when(department.getId()).thenReturn(id);
        return department;
    }

    private Major major(Long id) {
        Major major = org.mockito.Mockito.mock(Major.class);
        when(major.getId()).thenReturn(id);
        return major;
    }

    private ClassGroup classGroup() {
        return org.mockito.Mockito.mock(ClassGroup.class);
    }
}
