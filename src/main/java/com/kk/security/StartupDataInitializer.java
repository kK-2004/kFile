package com.kk.security;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StartupDataInitializer implements CommandLineRunner {
    private final AdminUserRepository userRepo;
    private final PasswordEncoder encoder;

    @Value("${ADMIN_INIT_USERNAME:admin}")
    private String initUsername;
    @Value("${ADMIN_INIT_PASSWORD:admin123}")
    private String initPassword;

    public StartupDataInitializer(AdminUserRepository userRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.encoder = encoder;
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
    }
}

