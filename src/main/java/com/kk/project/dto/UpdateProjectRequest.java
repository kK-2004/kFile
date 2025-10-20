package com.kk.project.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class UpdateProjectRequest {
    private String name;
    private List<String> allowedFileTypes;
    private Long fileSizeLimitBytes;
    private List<Map<String, Object>> expectedUserFields;
    private Long startAt; // epoch millis
    private Long endAt;   // epoch millis
    private Boolean allowResubmit;
    private Boolean allowMultiFiles;
    private Boolean allowOverdue;
    private String userSubmitStatusType;
    private String userSubmitStatusText;
    private String queryFieldKey;
    private Boolean offline;
    private String pathFieldKey;
    private java.util.List<String> pathSegments;
}
