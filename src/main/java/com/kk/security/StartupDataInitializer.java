package com.kk.security;

import com.kk.common.service.AppConfigService;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartupDataInitializer implements CommandLineRunner {
    private final AdminUserRepository userRepo;
    private final PasswordEncoder encoder;
    private final AppConfigService appConfigService;

    @Value("${ADMIN_INIT_USERNAME:admin}")
    private String initUsername;
    @Value("${ADMIN_INIT_PASSWORD:admin123}")
    private String initPassword;

    public StartupDataInitializer(AdminUserRepository userRepo, PasswordEncoder encoder,
                                  AppConfigService appConfigService) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.appConfigService = appConfigService;
    }

    @Override
    public void run(String... args) {
        userRepo.findByUsername(initUsername).orElseGet(() -> {
            AdminUser u = new AdminUser();
            u.setUsername(initUsername);
            u.setPassword(encoder.encode(initPassword));
            u.setRole("SUPER");
            u.setEnabled(true);
            return userRepo.save(u);
        });

        // 开放 API 默认数据源为后台必配项：首次启动写入初始值，保证系统设置中始终可见可改
        //（OpenFileService 仍保留 oss 兜底，防配置行被手工删除）
        if (appConfigService.getRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE) == null) {
            appConfigService.setRaw(AppConfigService.KEY_OPEN_API_DEFAULT_SOURCE, "oss");
            log.info("开放 API 默认数据源未配置，已初始化为 oss（可在系统设置中修改）");
        }
    }
}

