package com.kk.security.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.template.service.ProjectTemplateService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 用户管理接口出参不得包含密码哈希，且保留前端所需字段（guid 为字符串）。 */
class AdminUserControllerTest {

    private static final long GUID = 7351234567890123456L;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AdminUserRepository userRepo = mock(AdminUserRepository.class);
    private final AdminUserController controller = new AdminUserController(userRepo,
            mock(ProjectRepository.class), mock(ProjectPermissionRepository.class),
            new BCryptPasswordEncoder(), mock(ProjectTemplateService.class));

    private static AdminUser user() {
        AdminUser u = new AdminUser();
        u.setId(1L);
        u.setUsername("alice");
        u.setPassword("$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012");
        u.setRole("ADMIN");
        u.setQuotaBytes(1024L);
        u.setEnabled(true);
        u.setGuid(GUID);
        u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return u;
    }

    private static void assertSafeUserJson(JsonNode n) {
        assertThat(n.has("password")).isFalse();
        assertThat(n.get("id").asLong()).isEqualTo(1L);
        assertThat(n.get("username").asText()).isEqualTo("alice");
        assertThat(n.get("role").asText()).isEqualTo("ADMIN");
        assertThat(n.get("quotaBytes").asLong()).isEqualTo(1024L);
        assertThat(n.get("enabled").asBoolean()).isTrue();
        assertThat(n.has("createdAt")).isTrue();
        assertThat(n.get("guid").isTextual()).isTrue();
        assertThat(n.get("guid").asText()).isEqualTo(String.valueOf(GUID));
    }

    @Test
    void listDoesNotSerializePassword() throws Exception {
        when(userRepo.findAll()).thenReturn(List.of(user()));
        JsonNode arr = mapper.readTree(mapper.writeValueAsString(controller.list()));
        assertThat(arr).hasSize(1);
        assertSafeUserJson(arr.get(0));
    }

    @Test
    void createDoesNotSerializePassword() throws Exception {
        when(userRepo.save(any(AdminUser.class))).thenReturn(user());
        var view = controller.create(Map.of("username", "alice", "password", "secret", "role", "ADMIN"));
        assertSafeUserJson(mapper.readTree(mapper.writeValueAsString(view)));
    }

    @Test
    void entityItselfNeverSerializesPassword() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(user()));
        assertThat(n.has("password")).isFalse();
        assertThat(n.get("guid").isTextual()).isTrue();
    }
}
