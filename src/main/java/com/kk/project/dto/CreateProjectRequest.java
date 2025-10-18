package com.kk.project.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class CreateProjectRequest {
    private String name;
    private List<String> allowedFileTypes; // extensions without dot
    private Long fileSizeLimitBytes;       // per-file limit
    private List<Map<String, Object>> expectedUserFields; // optional schema list
    private Long startAt; // epoch millis
    private Long endAt;   // epoch millis
    private Boolean allowResubmit;
    private String pathFieldKey; // 上传路径字段key
    private java.util.List<String> pathSegments; // 上传路径层级
}
