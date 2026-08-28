package com.kk.sdk.model;

/** 简单直传初始化响应：预签名 PUT 直链 + 预生成 storageKey */
public record UploadInitResponse(String storageKey, String source, String putUrl, long expiresIn, Long fileId) {}
