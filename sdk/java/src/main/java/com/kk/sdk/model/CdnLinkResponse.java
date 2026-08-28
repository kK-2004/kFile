package com.kk.sdk.model;

/** 图片、音频、视频的稳定 CDN 预览链接响应。 */
public record CdnLinkResponse(String url, long expiresIn, boolean permanent, String contentType) {}
