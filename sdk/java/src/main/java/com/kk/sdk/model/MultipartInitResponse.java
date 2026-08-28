package com.kk.sdk.model;

import java.util.List;

/** 分片上传初始化响应；uploadedParts 为已上传分片（续传时跳过），alreadyDone 表示此前已完成 */
public record MultipartInitResponse(String uploadId, String chunkKeyPrefix, String storageKey,
                                     int totalChunks, Long fileId, List<UploadedPart> uploadedParts,
                                     boolean alreadyDone) {}
