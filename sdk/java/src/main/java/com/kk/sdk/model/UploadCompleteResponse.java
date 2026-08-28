package com.kk.sdk.model;

/** 简单直传完成响应 */
public record UploadCompleteResponse(Long fileId, String name, long size, String contentType) {}
