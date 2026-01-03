package com.kk.security;

import com.kk.security.service.AdminUserDetailsService;
import com.kk.security.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Value("${env.cors:http://localhost:5173}")
    private String cors;
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;
    @Value("${app.sso.enabled:true}")
    private boolean ssoEnabled;
    @Value("${app.sso.jwt.init-retry-seconds:30}")
    private long ssoJwtInitRetrySeconds;

    private final UserAccountService userAccountService;

    public SecurityConfig(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationProvider authenticationProvider(AdminUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        return p;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationProvider authenticationProvider) throws Exception {
        http
            // 同时支持：
            // - 主站用户/管理员：Bearer Token（资源服务器）
            // - 本地管理员：Cookie 会话（表单登录控制器保存的 Session）
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
            .cors(c -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new com.kk.security.handler.RestAuthenticationEntryPoint())
                .accessDeniedHandler(new com.kk.security.handler.RestAccessDeniedHandler())
            )
            .authorizeHttpRequests(reg -> reg
                // 登录与预检放行
                .requestMatchers("/api/admin/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/sso/status").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 用户提交页与公开接口：无需认证
                .requestMatchers(HttpMethod.GET,
                        "/api/projects",
                        "/api/projects/*",
                        "/api/projects/*/submissions/status",
                        "/test/*"
                ).permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/projects/*/submissions",
                        "/api/projects/*/submissions/validate",
                        "/api/projects/*/submissions/direct-init",
                        "/api/projects/*/submissions/direct-complete",
                        "/api/projects/*/submissions/direct-multipart-init",
                        "/api/projects/*/submissions/direct-multipart-sign",
                        "/api/projects/*/submissions/direct-multipart-complete"
                ).permitAll()
                // 文件直链/代理：公开下载
                .requestMatchers(HttpMethod.GET, "/file/oss/**").permitAll()
                // 其余一律要求已认证（Bearer 或 Cookie 会话其一）
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider)
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout
                .logoutUrl("/api/admin/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    try {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"ok\":true}");
                    } catch (Exception ignored) {}
                })
            );
        if (ssoEnabled && issuerUri != null && !issuerUri.isBlank()) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(new SiteJwtAuthConverter(userAccountService)))
            );
        }
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // CORS配置保留，前端必需
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(cors));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    // JWT 解码器，叠加 issuer 与 aud 验证（aud 需包含 k-File）
    @Bean
    public JwtDecoder jwtDecoder() {
        if (!ssoEnabled || issuerUri == null || issuerUri.isBlank()) {
            // 未配置 issuer 时：禁用 SSO（避免启动时尝试 discovery）
            return token -> { throw new JwtException("SSO disabled"); };
        }
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new JwtAudienceValidator("k-File");
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);

        // 主站 SSO 不可用时熔断：避免应用启动阶段因为 discovery/JWKs 拉取失败而崩溃
        return new com.kk.security.jwt.CircuitBreakerJwtDecoder(
                issuerUri,
                validator,
                Duration.ofSeconds(Math.max(1, ssoJwtInitRetrySeconds))
        );
    }
}
