package com.kk.share.controller;

import com.kk.common.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 首页 Hero 内容（公开接口，permitAll）。
 * 当前返回产品路线图 roadmapItems；后续可扩展其它首页可配置内容。
 */
@RestController
@RequestMapping("/api/hero")
@RequiredArgsConstructor
public class HeroController {

    private final AppConfigService appConfigService;

    /** 返回首页路线图。未配置时返回空列表（前端会 fallback 到内置默认）。 */
    @GetMapping
    public Map<String, Object> get() {
        List<Map<String, Object>> roadmap = appConfigService.getObjectList(AppConfigService.KEY_HERO_ROADMAP);
        return Map.of("roadmapItems", roadmap);
    }
}
