package com.kk.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kk.admin.task.ArchiveTaskService;
import com.kk.project.service.ProjectQueryService;
import com.kk.project.service.ProjectService;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.service.AdminPermissionService;
import com.kk.share.service.ShareLinkService;
import com.kk.template.service.ProjectTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * create_project 截止提醒入参对齐测试：校验规则与后台创建表单一致
 * （开启必须有 endAt、提前小时数 1-720、未传小时数默认 12），且预览包含截止提醒字段。
 */
class McpProjectToolsTest {

    private McpProjectTools tools;

    @BeforeEach
    void setUp() {
        AdminPermissionService adminPermissionService = mock(AdminPermissionService.class);
        AdminUserRepository userRepo = mock(AdminUserRepository.class);
        tools = new McpProjectTools(
                mock(ProjectTemplateService.class),
                mock(ProjectService.class),
                mock(ProjectQueryService.class),
                adminPermissionService,
                userRepo,
                mock(ArchiveTaskService.class),
                mock(ShareLinkService.class),
                mock(Environment.class));
        when(adminPermissionService.canCreateProject(any())).thenReturn(true);
        AdminUser user = new AdminUser();
        user.setUsername("alice");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 手填创建（不走模板），仅变化截止提醒相关入参，其余参数留空。 */
    private Map<String, Object> call(Long endAt, Boolean deadlineNotifyEnabled, Integer deadlineNotifyHours) {
        return tools.createProject(
                "p", false, null, null, endAt,
                deadlineNotifyEnabled, deadlineNotifyHours, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void deadlineNotifyEnabled_withoutEndAt_rejected() {
        assertThatThrownBy(() -> call(null, true, 12))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value()))
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("截止时间");
    }

    @Test
    void deadlineNotifyHours_outOfRange_rejected() {
        assertThatThrownBy(() -> call(1735689600000L, true, 721))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value()))
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("1-720");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preview_includesDeadlineNotify_andDefaultsHoursTo12() {
        Map<String, Object> out = call(1735689600000L, true, null);
        assertThat(out.get("requiresConfirmation")).isEqualTo(true);
        Map<String, Object> preview = (Map<String, Object>) out.get("preview");
        assertThat(preview.get("deadlineNotifyEnabled")).isEqualTo(true);
        assertThat(preview.get("deadlineNotifyHours")).isEqualTo(12);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preview_deadlineNotifyDisabled_byDefault() {
        Map<String, Object> out = call(null, null, null);
        Map<String, Object> preview = (Map<String, Object>) out.get("preview");
        assertThat(preview.get("deadlineNotifyEnabled")).isEqualTo(false);
    }
}
