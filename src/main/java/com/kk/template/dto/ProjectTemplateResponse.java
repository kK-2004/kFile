package com.kk.template.dto;

import com.kk.template.entity.ProjectTemplate;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 模板完整视图（SUPER 管理用），含所有可复用字段。
 * JSON 字段反序列化为对象结构，便于前端/MCP 直接消费。
 */
@Getter
@Setter
public class ProjectTemplateResponse {
    private Long id;
    private String name;
    private Long ownerId;

    private Object expectedUserFields;
    private String pathFieldKey;
    private List<String> pathSegments;
    private String userSubmitStatusType;
    private String userSubmitStatusText;
    private String queryFieldKey;
    private List<String> allowedSubmitterKeys;
    private Object allowedSubmitterList;
    private Boolean autoFileNamingEnabled;
    private Object autoFileNamingConfig;

    private Boolean allowResubmit;
    private Boolean allowMultiFiles;
    private Boolean allowOverdue;

    private Long createdAt; // epoch millis

    private static final com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();

    public static ProjectTemplateResponse from(ProjectTemplate t) {
        ProjectTemplateResponse r = new ProjectTemplateResponse();
        r.setId(t.getId());
        r.setName(t.getName());
        r.setOwnerId(t.getOwnerId());
        r.setPathFieldKey(t.getPathFieldKey());
        r.setUserSubmitStatusType(t.getUserSubmitStatusType());
        r.setUserSubmitStatusText(t.getUserSubmitStatusText());
        r.setQueryFieldKey(t.getQueryFieldKey());
        r.setAllowResubmit(t.getAllowResubmit());
        r.setAllowMultiFiles(t.getAllowMultiFiles());
        r.setAllowOverdue(t.getAllowOverdue());
        r.setAutoFileNamingEnabled(Boolean.TRUE.equals(t.getAutoFileNamingEnabled()));
        r.setCreatedAt(t.getCreatedAt() == null ? null : t.getCreatedAt().toEpochMilli());
        r.expectedUserFields = readJson(t.getExpectedUserFields(), Object.class);
        r.pathSegments = readList(t.getPathSegments());
        r.allowedSubmitterKeys = readList(t.getAllowedSubmitterKeys());
        r.allowedSubmitterList = readJson(t.getAllowedSubmitterList(), Object.class);
        r.autoFileNamingConfig = readJson(t.getAutoFileNamingConfig(), Object.class);
        return r;
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return M.readValue(json, M.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T readJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return M.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
