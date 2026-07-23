package com.kk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把带 {@code @Tool} 注解的 {@link McpProjectTools} 注册为 MCP 工具回调（任务 7.4）。
 *
 * <p>重写后的安全语义：
 * <ul>
 *   <li>身份只来自 HTTP bearer 认证结果——{@link
 *       com.kk.security.oauth.McpBearerAuthFilter} 在请求线程上建立的 {@link SecurityContext}。
 *       porter 不再读取或接受 {@code __kfile_access_token} 等模型可控隐藏参数。</li>
 *   <li>每次调用前剥离工具输入中任何自称身份的字段（{@code __kfile_access_token}），防止模型伪造。</li>
 *   <li>校验当前 SecurityContext 已认证；未认证（例如 MCP server 直接调用但无 HTTP bearer 上下文）→ 401。</li>
 *   <li>保留 Spring AI 自动 schema / 工具注册，调用委托给既有 {@link McpProjectTools}。</li>
 * </ul>
 *
 * <p>注意：Spring AI WebMVC Streamable transport 以 SYNC 模式在请求线程内同步执行 tools/call，
 * 因此 {@link McpBearerAuthFilter} 设置的 SecurityContext 在工具执行边界可见；调用结束后由过滤器链/
 * 请求结束自动清理，无需此处手动恢复。但为稳健起见，本装饰器不修改已建立的 SecurityContext。
 */
@Slf4j
@Configuration
public class McpToolRegistration {
    private static final ObjectMapper M = new ObjectMapper();
    /** 旧隐藏身份参数：必须从模型输入中剥离，且不得用于建立身份。 */
    private static final String[] FORBIDDEN_INPUT_KEYS = {"__kfile_access_token"};

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(McpProjectTools tools) {
        ToolCallbackProvider delegate =
                MethodToolCallbackProvider.builder().toolObjects(tools).build();
        return () ->
                Arrays.stream(delegate.getToolCallbacks())
                        .map(this::withIdentityGuard)
                        .toArray(ToolCallback[]::new);
    }

    private ToolCallback withIdentityGuard(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return callInternal(delegate, toolInput, null);
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return callInternal(delegate, toolInput, toolContext);
            }
        };
    }

    private String callInternal(
            ToolCallback delegate, String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
        // 1. 剥离任何模型可控的自称身份字段
        String sanitized = stripForbiddenKeys(toolInput);
        // 2. 校验身份只来自 HTTP bearer 认证结果（当前 SecurityContext）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            log.warn("BIZ action=MCP_TOOL_UNAUTH 拒绝无 HTTP bearer 上下文的工具调用");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        // 3. 直接调用；身份上下文已由 McpBearerAuthFilter 在请求线程建立，无需手动设置/恢复
        if (toolContext == null) {
            return delegate.call(sanitized);
        }
        return delegate.call(sanitized, toolContext);
    }

    /** 从工具输入 JSON 中删除禁止的键（防止模型注入身份参数）。 */
    private String stripForbiddenKeys(String toolInput) {
        try {
            if (toolInput == null || toolInput.isBlank()) {
                return "{}";
            }
            JsonNode node = M.readTree(toolInput);
            if (!(node instanceof ObjectNode objectNode)) {
                return toolInput;
            }
            boolean changed = false;
            for (String key : FORBIDDEN_INPUT_KEYS) {
                if (objectNode.has(key)) {
                    objectNode.remove(key);
                    changed = true;
                    log.warn("BIZ action=MCP_TOOL_STRIP 剥离禁止的输入字段 key={}", key);
                }
            }
            return changed ? M.writeValueAsString(objectNode) : toolInput;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数解析失败", e);
        }
    }
}
