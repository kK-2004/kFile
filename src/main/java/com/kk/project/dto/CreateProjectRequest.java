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
    private Boolean allowMultiFiles;     // 是否允许一次提交多个文件
    private Boolean allowOverdue; // 是否允许逾期提交
    private String userSubmitStatusType; // info/warning/success/danger
    private String userSubmitStatusText; // 自定义提示文案
    private String queryFieldKey;        // 查询提交状态所用字段（key）
    private String pathFieldKey; // 上传路径字段key
    private java.util.List<String> pathSegments; // 上传路径层级
}
