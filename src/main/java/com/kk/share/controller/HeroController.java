package com.kk.share.controller;

import com.kk.common.service.AppConfigService;
import com.kk.config.McpOAuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页 Hero 内容（公开接口，permitAll）。
 * 当前返回产品路线图 roadmapItems 与 MCP 连接 URL；后续可扩展其它首页可配置内容。
 */
@RestController
@RequestMapping("/api/hero")
@RequiredArgsConstructor
public class HeroController {

    private final AppConfigService appConfigService;
    private final McpOAuthProperties mcpOAuthProperties;

    /** 返回首页路线图与 MCP 连接信息。未配置时 roadmap 返回空列表（前端 fallback 到内置默认）。 */
    @GetMapping
    public Map<String, Object> get() {
        List<Map<String, Object>> roadmap = appConfigService.getObjectList(AppConfigService.KEY_HERO_ROADMAP);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("roadmapItems", roadmap);
        // MCP Streamable HTTP 远程入口（含正确环境的公共基址，dev=localhost:9000，prod=对外域名）
        resp.put("mcpUrl", mcpOAuthProperties.resourceUrl());
        return resp;
    }
}
