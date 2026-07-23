package com.kk.security;

import com.kk.security.oauth.McpBearerAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 禁用 {@link McpBearerAuthFilter} 的全局 servlet 自动注册。
 *
 * <p>该过滤器是 {@code @Component}，Spring Boot 默认会把它注册到全局 servlet 容器。但我们只想让它
 * 在 {@code /mcp} SecurityFilterChain 中通过 {@code addFilterBefore} 生效，避免在所有请求上重复过滤。
 * 用 FilterRegistrationBean 设 enabled=false 即可禁用自动注册。
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<McpBearerAuthFilter> mcpBearerAuthFilterRegistration(
            McpBearerAuthFilter filter) {
        FilterRegistrationBean<McpBearerAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
