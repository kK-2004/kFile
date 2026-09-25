package com.kk.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import com.kk.project.entity.Project;
import com.kk.project.repo.SubmissionRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.entity.ProjectPermission;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * 管理端项目列表（myProjects）回归测试：
 * SUPER 返回全部并可解析创建者用户名；ADMIN 按权限过滤且同样返回创建者；无归属项目 creator 为 null。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectQueryServiceTest {

    @Mock private ProjectService projectService;
    @Mock private AdminUserRepository userRepo;
    @Mock private ProjectPermissionRepository permRepo;
    @Mock private SubmissionRepository submissionRepository;

    private ProjectQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProjectQueryService(projectService, userRepo, permRepo, submissionRepository);
    }

    private Project project(long id, Long ownerUserId) {
        Project p = new Project();
        p.setId(id);
        p.setName("p" + id);
        p.setOwnerUserId(ownerUserId);
        return p;
    }

    private AdminUser user(long id, String username) {
        AdminUser u = new AdminUser();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    private Authentication auth(String username, String... roles) {
        return new TestingAuthenticationToken(username, "n/a", roles);
    }

    @Test
    void superListResolvesCreatorAndLeavesUnknownNull() {
        Project owned = project(1L, 10L);
        Project orphan = project(2L, null);
        when(projectService.list()).thenReturn(List.of(owned, orphan));
        when(userRepo.findAllById(anyIterable())).thenReturn(List.of(user(10L, "super01")));

        List<com.kk.project.dto.ProjectResponse> out =
                service.myProjects(auth("super01", "ROLE_SUPER"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getCreator()).isEqualTo("super01");
        assertThat(out.get(0).getCanEdit()).isTrue();
        assertThat(out.get(0).getCanDelete()).isTrue();
        assertThat(out.get(1).getCreator()).isNull();
    }

    @Test
    void adminListFiltersByPermissionAndResolvesCreator() {
        Project owned = project(1L, 20L);
        AdminUser admin = user(20L, "admin01");
        ProjectPermission pp = new ProjectPermission();
        pp.setUser(admin);
        pp.setProject(owned);
        pp.setCanEdit(true);
        pp.setCanDelete(false);
        when(userRepo.findByUsername("admin01")).thenReturn(Optional.of(admin));
        when(permRepo.findByUser(admin)).thenReturn(List.of(pp));
        when(userRepo.findAllById(anyIterable())).thenReturn(List.of(admin));

        List<com.kk.project.dto.ProjectResponse> out =
                service.myProjects(auth("admin01", "ROLE_ADMIN"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getCreator()).isEqualTo("admin01");
        assertThat(out.get(0).getCanEdit()).isTrue();
        assertThat(out.get(0).getCanDelete()).isFalse();
    }

    @Test
    void ownerWithoutMatchingAdminUserYieldsNullCreator() {
        Project owned = project(1L, 99L);
        when(projectService.list()).thenReturn(List.of(owned));
        when(userRepo.findAllById(anyIterable())).thenReturn(List.of());

        List<com.kk.project.dto.ProjectResponse> out =
                service.myProjects(auth("super01", "ROLE_SUPER"));

        assertThat(out.get(0).getCreator()).isNull();
    }
}
