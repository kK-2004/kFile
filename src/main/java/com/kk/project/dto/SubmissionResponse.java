package com.kk.project.dto;

import com.kk.project.entity.Submission;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SubmissionResponse {
    private Long id;
    private Object submitterInfo; // keep as raw JSON decoded if possible
    private Object fileUrls;      // raw JSON array or string
    private Integer submitCount;
    private Boolean expired;
    private String ipAddress;
    private String userAgent;
    private String osName;
    private String osVersion;
    private String browserName;
    private String browserVersion;
    private String deviceType;
    private String ipCountry;
    private String ipProvince;
    private String ipCity;
    private Long createdAt; // epoch millis
    private Long updatedAt; // epoch millis
    private String submitterFingerprint;

    public static SubmissionResponse from(Submission s) {
        SubmissionResponse r = new SubmissionResponse();
        r.setId(s.getId());
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            r.setSubmitterInfo(om.readTree(s.getSubmitterInfo()));
        } catch (Exception e) {
            r.setSubmitterInfo(s.getSubmitterInfo());
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> urls = om.readValue(s.getFileUrls(), om.getTypeFactory().constructCollectionType(List.class, String.class));
            r.setFileUrls(urls);
        } catch (Exception e) {
            r.setFileUrls(s.getFileUrls());
        }
        r.setSubmitCount(s.getSubmitCount());
        r.setExpired(s.getExpired());
        r.setIpAddress(s.getIpAddress());
        r.setUserAgent(s.getUserAgent());
        r.setOsName(s.getOsName());
        r.setOsVersion(s.getOsVersion());
        r.setBrowserName(s.getBrowserName());
        r.setBrowserVersion(s.getBrowserVersion());
        r.setDeviceType(s.getDeviceType());
        r.setIpCountry(s.getIpCountry());
        r.setIpProvince(s.getIpProvince());
        r.setIpCity(s.getIpCity());
        r.setSubmitterFingerprint(s.getSubmitterFingerprint());
        r.setCreatedAt(s.getCreatedAt() == null ? null : s.getCreatedAt().toEpochMilli());
        r.setUpdatedAt(s.getUpdatedAt() == null ? null : s.getUpdatedAt().toEpochMilli());
        return r;
    }
}

