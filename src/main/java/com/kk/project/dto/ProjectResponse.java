package com.kk.project.dto;

import com.kk.project.entity.Project;
import lombok.Getter;
import lombok.Setter;

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
    private Boolean allowMultiFiles;
    private Boolean allowOverdue;
    private Boolean offline;
    private String pathFieldKey;
    private java.util.List<String> pathSegments;
    private Boolean expired;
    private String userSubmitStatusType;
    private String userSubmitStatusText;
    private String queryFieldKey;
    private java.util.List<String> allowedSubmitterKeys;
    private Object allowedSubmitterList;
    // Public hint: whether restriction is enabled (keys + list configured)
    private Boolean submitterRestrictionEnabled;

    public static ProjectResponse from(Project p, List<String> types, Object expected) {
        return from(p, types, expected, true);
    }

    public static ProjectResponse from(Project p, List<String> types, Object expected, boolean includeAllowedList) {
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
        r.setAllowMultiFiles(p.getAllowMultiFiles());
        r.setAllowOverdue(p.getAllowOverdue());
        r.setPathFieldKey(p.getPathFieldKey());
        r.setOffline(p.getOffline());
        r.setUserSubmitStatusType(p.getUserSubmitStatusType());
        r.setUserSubmitStatusText(p.getUserSubmitStatusText());
        r.setQueryFieldKey(p.getQueryFieldKey());
        try {
            if (p.getAllowedSubmitterKeys() != null) {
                r.setAllowedSubmitterKeys(new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        p.getAllowedSubmitterKeys(), java.util.List.class));
            }
        } catch (Exception ignored) {}
        if (includeAllowedList) {
            try {
                if (p.getAllowedSubmitterList() != null) {
                    r.setAllowedSubmitterList(new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            p.getAllowedSubmitterList(), Object.class));
                }
            } catch (Exception ignored) {}
        }
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
        // set restriction enabled flag (keys + list present on entity)
        try {
            boolean hasKeys = p.getAllowedSubmitterKeys() != null && !p.getAllowedSubmitterKeys().isBlank();
            boolean hasList = p.getAllowedSubmitterList() != null && !p.getAllowedSubmitterList().isBlank();
            r.setSubmitterRestrictionEnabled(hasKeys && hasList);
        } catch (Exception ignored) {}
        return r;
    }
}
