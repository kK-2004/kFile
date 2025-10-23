package com.kk.security;

import com.kk.security.service.AdminUserDetailsService;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Value("${env.cors:http://localhost:5173}")
    private String cors;
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
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
            .cors(c -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new com.kk.security.handler.RestAuthenticationEntryPoint())
                .accessDeniedHandler(new com.kk.security.handler.RestAccessDeniedHandler())
            )
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(
                        "/api/admin/auth/**"
                ).permitAll()
                // allow public read of projects
                .requestMatchers(HttpMethod.GET, "/api/projects", "/api/projects/*").permitAll()
                // allow public file proxy via internal OSS (GET/HEAD)
                .requestMatchers(HttpMethod.GET, "/file/oss/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/file/oss/**").permitAll()
                // allow public submit to project + 直传初始化/完成
                .requestMatchers(HttpMethod.POST, "/api/projects/*/submissions").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/projects/*/submissions/direct-init").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/projects/*/submissions/direct-complete").permitAll()
                // allow public check latest status
                .requestMatchers(HttpMethod.GET, "/api/projects/*/submissions/status").permitAll()
                // CORS preflight
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider)
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.logoutUrl("/api/admin/auth/logout"));
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
}
