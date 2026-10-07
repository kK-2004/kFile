package com.kk.openapi.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kk.common.GlobalExceptionHandler;
import com.kk.openapi.OpenAppPrincipal;
import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.service.OpenAppService;
import com.kk.openapi.service.OpenFileService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /api/open/files/batch-delete 控制器测试：当前应用身份传递与响应 JSON 契约、
 * 服务层异常到 HTTP 状态映射（400/404/409）、身份缺失拒绝。
 * appToken 缺失/无效/禁用 → 401 由 /api/open/** 安全链保证（OpenAppAuthFilterTest 覆盖，
 * 过滤器按路径前缀匹配、与本端点无关具体路径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenFileControllerBatchDeleteTest {

    @Mock private OpenFileService openFileService;
    @Mock private OpenAppService openAppService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.standaloneSetup(new OpenFileController(openFileService, openAppService))
                .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
                .build();
    }

    private Authentication appAuth() {
        return new UsernamePasswordAuthenticationToken(new OpenAppPrincipal(7L, "crm"), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPEN_APP")));
    }

    private org.springframework.test.web.servlet.RequestBuilder request(String body, Authentication auth) {
        var req = post("/api/open/files/batch-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return auth == null ? req : req.principal(auth);
    }

    @Test
    void passesCurrentAppIdentityAndReturnsCounters() throws Exception {
        OpenApp app = new OpenApp();
        app.setId(7L);
        when(openAppService.requireApp(7L)).thenReturn(app);
        when(openFileService.deleteFiles(app, List.of(1L, 2L)))
                .thenReturn(new OpenFileService.BatchDeleteResult(2, 0));

        mockMvc.perform(request("{\"fileIds\":[1,2]}", appAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedFiles").value(2))
                .andExpect(jsonPath("$.failedObjects").value(0));

        // 身份传递：principal(7) → requireApp → 服务层收到当前应用实体
        verify(openFileService).deleteFiles(
                argThat(a -> a != null && Long.valueOf(7L).equals(a.getId())), eq(List.of(1L, 2L)));
    }

    @Test
    void missingAppIdentityRejected() throws Exception {
        mockMvc.perform(request("{\"fileIds\":[1]}", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("应用身份缺失"));
    }

    @Test
    void principalOfWrongTypeRejected() throws Exception {
        Authentication wrong = new UsernamePasswordAuthenticationToken("some-user", null);
        mockMvc.perform(request("{\"fileIds\":[1]}", wrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("应用身份缺失"));
    }

    @Test
    void serviceBadRequestPropagatesAs400() throws Exception {
        OpenApp app = new OpenApp();
        app.setId(7L);
        when(openAppService.requireApp(7L)).thenReturn(app);
        when(openFileService.deleteFiles(eq(app), anyList()))
                .thenThrow(new IllegalArgumentException("单次最多删除 100 个文件"));

        mockMvc.perform(request("{\"fileIds\":[]}", appAuth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("单次最多删除 100 个文件"));
    }

    @Test
    void serviceNotFoundPropagatesAs404() throws Exception {
        OpenApp app = new OpenApp();
        app.setId(7L);
        when(openAppService.requireApp(7L)).thenReturn(app);
        when(openFileService.deleteFiles(eq(app), anyList()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));

        mockMvc.perform(request("{\"fileIds\":[1,99]}", appAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    void serviceConflictPropagatesAs409() throws Exception {
        OpenApp app = new OpenApp();
        app.setId(7L);
        when(openAppService.requireApp(7L)).thenReturn(app);
        when(openFileService.deleteFiles(eq(app), anyList()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "文件尚未完成上传，不能删除: 5"));

        mockMvc.perform(request("{\"fileIds\":[5]}", appAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("文件尚未完成上传，不能删除: 5"));
    }
}
