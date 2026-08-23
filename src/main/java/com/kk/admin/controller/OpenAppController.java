package com.kk.admin.controller;

import com.kk.openapi.entity.OpenApp;
import com.kk.openapi.service.OpenAppMigrationService;
import com.kk.openapi.service.OpenAppMigrationService.MigrationResult;
import com.kk.openapi.service.OpenAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放应用管理（SUPER）。token 明文仅在创建/轮换响应出现一次；列表/详情不返回明文与哈希。
 * 删除为级联清理：应用名下全部文件（对象 + 节点 + 分片残留）随应用记录一起删除，强确认前可查 stats。
 */
@RestController
@RequestMapping("/api/admin/open-apps")
@RequiredArgsConstructor
public class OpenAppController {

    private final OpenAppService openAppService;
    private final OpenAppMigrationService migrationService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER')")
    public List<Map<String, Object>> list() {
        return openAppService.list().stream().map(OpenAppController::toMap).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, Object> create(@RequestBody CreateReq req) {
        OpenAppService.CreatedApp created =
                openAppService.create(req.appName(), req.description(), req.rootPath());
        Map<String, Object> resp = toMap(created.app());
        resp.put("token", created.token());
        return resp;
    }

    /**
     * 修改 description / rootPath。rootPath 变更会同步迁移该应用全部已登记文件至新路径，
     * 请求阻塞至迁移完成后返回 moved/skipped 统计；rootPath 为空串=恢复默认目录。
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        OpenApp app = openAppService.requireApp(id);
        Map<String, Object> resp;
        if (req.rootPath() != null) {
            MigrationResult result = migrationService.changeRootPath(app, req.rootPath());
            app = openAppService.requireApp(id);
            resp = toMap(app);
            resp.put("migration", Map.of(
                    "moved", result.moved(),
                    "skipped", result.skipped(),
                    "skippedFiles", result.skippedFiles()));
        } else {
            if (req.description() != null) {
                openAppService.updateDescription(id, req.description());
            }
            app = openAppService.requireApp(id);
            resp = toMap(app);
        }
        return resp;
    }

    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, String> rotate(@PathVariable Long id) {
        return Map.of("token", openAppService.rotate(id));
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, Object> setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        openAppService.setEnabled(id, req.enabled());
        return toMap(openAppService.requireApp(id));
    }

    /** 删除前统计（强确认弹窗展示文件数与总大小） */
    @GetMapping("/{id}/stats")
    @PreAuthorize("hasRole('SUPER')")
    public OpenAppService.AppFileStats stats(@PathVariable Long id) {
        return openAppService.stats(id);
    }

    /** 级联删除：应用名下全部文件（对象 + 节点 + 分片残留）+ 应用记录（token 立即失效） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER')")
    public Map<String, Object> delete(@PathVariable Long id) {
        OpenAppService.DeleteResult result = openAppService.deleteApp(id);
        return Map.of("ok", true, "deletedFiles", result.deletedFiles(), "failedObjects", result.failedObjects());
    }

    private static Map<String, Object> toMap(OpenApp app) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", app.getId());
        m.put("appName", app.getAppName());
        m.put("description", app.getDescription());
        m.put("rootPath", app.getRootPath());
        m.put("rootPathEffective", OpenAppService.effectiveRoot(app));
        m.put("enabled", app.isEnabled());
        m.put("lastUsedAt", app.getLastUsedAt());
        m.put("createdAt", app.getCreatedAt());
        return m;
    }

    // ===== 请求 DTO =====

    public record CreateReq(String appName, String description, String rootPath) {}

    public record UpdateReq(String description, String rootPath) {}

    public record EnabledReq(boolean enabled) {}
}
