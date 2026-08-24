package com.kk.sdk.model;

/** 已上传分片（服务端 ListParts 结果，partNumber 从 1 开始） */
public record UploadedPart(int partNumber, String etag) {}
