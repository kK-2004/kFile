package com.kk.project.dto;

import com.kk.project.entity.Project;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ProjectResponse {
    private Long id;
    private String name;
    private List<String> allowedFileTypes;
    private Long fileSizeLimitBytes;
    private Integer totalSubmitters;
    private Object expectedUserFields;
    private Long startAt; // epoch millis
    private Long endAt;   // epoch millis
    private Boolean allowResubmit;
    private Boolean offline;
    private String pathFieldKey;
    private java.util.List<String> pathSegments;
    private Boolean expired;

    public static ProjectResponse from(Project p, List<String> types, Object expected) {
        ProjectResponse r = new ProjectResponse();
        r.setId(p.getId());
        r.setName(p.getName());
        r.setAllowedFileTypes(types);
        r.setFileSizeLimitBytes(p.getFileSizeLimitBytes());
        r.setTotalSubmitters(p.getTotalSubmitters());
        r.setExpectedUserFields(expected);
        r.setStartAt(p.getStartAt() == null ? null : p.getStartAt().toEpochMilli());
        r.setEndAt(p.getEndAt() == null ? null : p.getEndAt().toEpochMilli());
        r.setAllowResubmit(p.getAllowResubmit());
        r.setPathFieldKey(p.getPathFieldKey());
        r.setOffline(p.getOffline());
        try {
            java.time.Instant now = java.time.Instant.now();
            Boolean exp = (p.getEndAt() != null && now.isAfter(p.getEndAt()));
            r.setExpired(exp);
        } catch (Exception ignored) {}
        try {
            if (p.getPathSegments() != null) {
                r.setPathSegments(new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        p.getPathSegments(), java.util.List.class));
            }
        } catch (Exception ignored) {}
        return r;
    }
}
