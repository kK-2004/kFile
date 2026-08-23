package com.kk.sdk.model;

/** 分片上传完成响应 */
public record MultipartCompleteResponse(String storageKey, Long fileId, long size) {}
