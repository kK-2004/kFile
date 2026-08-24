package com.kk.openapi.controller;

import com.kk.openapi.OpenAppPrincipal;
import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.service.OpenAppService;
import com.kk.openapi.service.OpenFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenFileControllerTest {

    @Mock private OpenFileService openFileService;
    @Mock private OpenAppService openAppService;

    private OpenFileController controller;
    private OpenApp app;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        controller = new OpenFileController(openFileService, openAppService);
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "https://file.example.com/");
        app = new OpenApp();
        app.setId(7L);
        app.setAppName("demo");
        auth = new UsernamePasswordAuthenticationToken(new OpenAppPrincipal(7L, "demo"), null);
        when(openAppService.requireApp(7L)).thenReturn(app);
    }

    @Test
    void returnsAbsoluteStableCdnUrlForSdk() {
        when(openFileService.cdnLink(app, 9L, null))
                .thenReturn(new OpenFileService.CdnLinkResult("token", 0L, true, "image/png"));

        OpenFileController.CdnLinkResponse response = controller.cdnLink(
                new OpenFileController.CdnLinkReq(9L, null), auth, new MockHttpServletRequest());

        assertThat(response.url()).isEqualTo("https://file.example.com/file/cdn/token");
        assertThat(response.expiresIn()).isZero();
        assertThat(response.permanent()).isTrue();
        assertThat(response.contentType()).isEqualTo("image/png");
    }
}
