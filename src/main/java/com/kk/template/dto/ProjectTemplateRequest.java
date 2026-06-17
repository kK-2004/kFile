package com.kk.template.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 保存/更新模板入参：只含可复用字段 + name。
 * 不含 name 以外的项目特有字段（startAt/endAt/fileSizeLimitBytes/allowedFileTypes/offline）。
 */
@Getter
@Setter
public class ProjectTemplateRequest {
    private String name;

    // 可复用字段（与 CreateProjectRequest 同款）
    private List<Map<String, Object>> expectedUserFields;
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
}
