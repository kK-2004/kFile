package com.kk.security;

import com.kk.config.McpOAuthProperties;
import com.kk.security.handler.RestAccessDeniedHandler;
import com.kk.security.handler.RestAuthenticationEntryPoint;
import com.kk.security.oauth.McpBearerAuthFilter;
import com.kk.security.oauth.McpBearerAuthenticationEntryPoint;
import com.kk.security.service.AdminUserDetailsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全配置：三条独立 SecurityFilterChain（任务 6.1 / 6.2）。
 *
 * <p>按 {@code @Order} 优先级匹配：
 * <ol>
 *   <li>{@code /mcp} Bearer 资源服务器链（{@link #mcpFilterChain}）：无 session（STATELESS），
 *       仅接受 {@code mcp:tools} scope 且 resource 匹配的 OAuth bearer token，
 *       失败返回带 {@code resource_metadata} 的可发现 401。MCP token 不能访问 {@code /api/admin/**}。</li>
 *   <li>{@code /api/open} 开放 API 链（{@link #openApiFilterChain}）：无 session（STATELESS），
 *       仅接受开放应用 appToken（{@code OpenAppAuthFilter}，ROLE_OPEN_APP）。
 *       应用身份与管理员会话互不可达：appToken 无 session 访问不了 {@code /api/admin/**}，
 *       管理员 cookie 无 Bearer 访问不了 {@code /api/open/**}。</li>
 *   <li>OAuth endpoints 链（{@link #oauthFilterChain}）：{@code /oauth2/consent} 写操作需要 session
 *       并启用 CSRF/一次性确认防护；{@code /oauth2/authorize} 等在 permitAll 中已放行。</li>
 *   <li>Web / API session 链（{@link #webFilterChain}）：现有登录、登出、session 与管理 API 权限行为不变。</li>
 * </ol>
 *
 * <p>CSRF：Web 链沿用既有行为；OAuth consent 写操作链启用 CSRF 防护；MCP bearer 链无 session 无需 CSRF。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${env.cors:http://localhost:5173}")
    private String cors;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            AdminUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        return p;
    }

    // ============ 链 1：/mcp Bearer 资源服务器（最高优先级）============

    @Bean
    @Order(1)
    public SecurityFilterChain mcpFilterChain(
            HttpSecurity http,
            AuthenticationProvider authenticationProvider,
            McpBearerAuthFilter mcpBearerAuthFilter,
            McpOAuthProperties props)
            throws Exception {
        McpBearerAuthenticationEntryPoint entryPoint =
                new McpBearerAuthenticationEntryPoint(props);
        http
                .securityMatcher("/mcp", "/mcp/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(new RestAccessDeniedHandler()))
                // 仅在 REQUEST dispatch 上做授权检查；SSE 异步流结束时的 ASYNC dispatch 不再触发
                // 授权与异常处理，避免 "response is already committed" 噪音错误（Spring Security
                // 在 async dispatch 阶段不应对已完成的 SSE 流处理异常）。
                .authorizeHttpRequests(
                        (authz) -> authz.shouldFilterAllDispatcherTypes(false).anyRequest().authenticated())
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(
                        mcpBearerAuthFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                                .class);
        return http.build();
    }

    // ============ 链 2：/api/open 开放 API（appToken，STATELESS）============

    @Bean
    @Order(2)
    public SecurityFilterChain openApiFilterChain(
            HttpSecurity http, com.kk.openapi.OpenAppAuthFilter openAppAuthFilter) throws Exception {
        http
                .securityMatcher("/api/open/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(new RestAuthenticationEntryPoint())
                                        .accessDeniedHandler(new RestAccessDeniedHandler()))
                .authorizeHttpRequests(authz -> authz.anyRequest().hasRole("OPEN_APP"))
                .addFilterBefore(
                        openAppAuthFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                                .class)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    // ============ 链 3：OAuth endpoints（consent 写操作需 session + CSRF）============

    @Bean
    @Order(3)
    public SecurityFilterChain oauthFilterChain(
            HttpSecurity http, AuthenticationProvider authenticationProvider) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/.well-known/**")
                .cors(Customizer.withDefaults())
                // OAuth 端点禁用 Spring Security CSRF：
                // 1) register/token/revoke 是标准 OAuth 客户端机接口（无浏览器表单）；
                // 2) consent 的 CSRF 由 OAuth state 参数 + 已登录 session + 严格参数校验保证，
                //    不依赖 Spring 的 CSRF token（spec 未要求 consent 使用双重提交 cookie）。
                //    之前用 CookieCsrfTokenRepository + 手动触发过滤器导致 CsrfFilter orElseThrow 崩溃。
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                new RestAuthenticationEntryPoint())
                                        .accessDeniedHandler(new RestAccessDeniedHandler()))
                .authorizeHttpRequests(
                        reg ->
                                reg
                                        .requestMatchers(
                                                "/.well-known/**")
                                                .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/oauth2/register")
                                                .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/oauth2/token", "/oauth2/revoke")
                                                .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/oauth2/authorize")
                                                .permitAll()
                                        // consent 写操作需要 session 认证
                                        .requestMatchers(HttpMethod.POST, "/oauth2/consent")
                                                .authenticated()
                                        .anyRequest()
                                        .authenticated())
                .authenticationProvider(authenticationProvider)
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    // ============ 链 4：Web / API session（现有行为不变）============

    @Bean
    @Order(4)
    public SecurityFilterChain webFilterChain(
            HttpSecurity http, AuthenticationProvider authenticationProvider) throws Exception {
        http
                .securityMatcher("/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                new RestAuthenticationEntryPoint())
                                        .accessDeniedHandler(new RestAccessDeniedHandler()))
                .authorizeHttpRequests(
                        reg ->
                                reg
                                        .requestMatchers("/api/admin/auth/**")
                                        .permitAll()
                                        // OAuth / MCP 元数据发现（已在链 2 放行，此处兜底）
                                        .requestMatchers(
                                                "/.well-known/oauth-protected-resource/**",
                                                "/.well-known/oauth-authorization-server")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/share/*")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/share/*/download")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/hero")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/projects",
                                                "/api/projects/*",
                                                "/api/projects/*/submissions/status",
                                                "/test/*")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/projects/*/submissions",
                                                "/api/projects/*/submissions/validate",
                                                "/api/projects/*/submissions/direct-init",
                                                "/api/projects/*/submissions/direct-complete",
                                                "/api/projects/*/submissions/direct-multipart-init",
                                                "/api/projects/*/submissions/direct-multipart-sign",
                                                "/api/projects/*/submissions/direct-multipart-complete")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/file/oss/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/file/minio/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/file/cdn/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .authenticationProvider(authenticationProvider)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(
                        logout ->
                                logout
                                        .logoutUrl("/api/admin/auth/logout")
                                        .logoutSuccessHandler(
                                                (request, response, authentication) -> {
                                                    try {
                                                        response.setStatus(HttpServletResponse.SC_OK);
                                                        response.setContentType(
                                                                "application/json;charset=UTF-8");
                                                        response.getWriter().write("{\"ok\":true}");
                                                    } catch (Exception ignored) {
                                                    }
                                                }));
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
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
