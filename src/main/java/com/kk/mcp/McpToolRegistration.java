package com.kk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kk.security.service.McpTokenService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

/**
 * 把带 @Tool 注解的 McpProjectTools 注册为 MCP 工具回调。
 *
 * Spring AI 1.0.x 的 MCP 自动配置不会自动扫描 @Component 上的 @Tool 方法，
 * 必须显式提供一个 ToolCallbackProvider Bean，工具才会暴露给 MCP 客户端。
 * ToolCallbacks.from(...) / MethodToolCallbackProvider 会读取 @Tool/@ToolParam
 * 元数据并生成可被 MCP server 注册的回调。
 */
@Configuration
public class McpToolRegistration {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String ACCESS_TOKEN_ARG = "__kfile_access_token";

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(McpProjectTools tools,
                                                        McpTokenService tokenService) {
        ToolCallbackProvider delegate = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
        return () -> Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> withMcpAccessToken(callback, tokenService))
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback withMcpAccessToken(ToolCallback delegate,
                                            McpTokenService tokenService) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                TokenInput tokenInput = extractToken(toolInput);
                return callWithToken(delegate, tokenService, tokenInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                TokenInput tokenInput = extractToken(toolInput);
                return callWithToken(delegate, tokenService, tokenInput, toolContext);
            }
        };
    }

    private String callWithToken(ToolCallback delegate,
                                 McpTokenService tokenService,
                                 TokenInput tokenInput,
                                 ToolContext toolContext) {
        McpTokenService.AuthResult result = tokenService.authenticate(tokenInput.accessToken());
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                result.user().getUsername(), null, result.authorities());
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            if (toolContext == null) {
                return delegate.call(tokenInput.sanitizedInput());
            }
            return delegate.call(tokenInput.sanitizedInput(), toolContext);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private TokenInput extractToken(String toolInput) {
        try {
            JsonNode node = M.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
            }
            JsonNode tokenNode = objectNode.get(ACCESS_TOKEN_ARG);
            String token = tokenNode == null || tokenNode.isNull() ? null : tokenNode.asText(null);
            if (token == null || token.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
            }
            objectNode.remove(ACCESS_TOKEN_ARG);
            return new TokenInput(token, M.writeValueAsString(objectNode));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数解析失败", e);
        }
    }

    private record TokenInput(String accessToken, String sanitizedInput) {}
}
