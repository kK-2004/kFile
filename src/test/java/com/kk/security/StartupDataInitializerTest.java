package com.kk.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.common.service.AppConfigService;
import com.kk.security.repo.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 开放 API 默认数据源为后台必配项：首次启动（配置缺失）写入初始值 oss；已配置则不覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StartupDataInitializerTest {

    @Mock private AdminUserRepository userRepo;
    @Mock private PasswordEncoder encoder;
    @Mock private AppConfigService appConfigService;

    @InjectMocks
    private StartupDataInitializer initializer;

    private void init() {
        ReflectionTestUtils.setField(initializer, "initUsername", "admin");
        ReflectionTestUtils.setField(initializer, "initPassword", "admin123");
        when(userRepo.findByUsername(anyString())).thenReturn(java.util.Optional.of(new com.kk.security.entity.AdminUser()));
    }

    @Test
    void initializesDefaultSourceWhenAbsent() {
        init();
        when(appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE)).thenReturn(null);

        initializer.run();

        verify(appConfigService).setRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE, "oss");
    }

    @Test
    void keepsExistingConfiguredValue() {
        init();
        when(appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE)).thenReturn("minio");

        initializer.run();

        verify(appConfigService, never()).setRaw(anyString(), anyString());
    }
}
