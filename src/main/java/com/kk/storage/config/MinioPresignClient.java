package com.kk.storage.config;

import io.minio.MinioClient;

/**
 * 仅用于签发预签名直链的 MinIO 客户端：端点为 minio.public-endpoint（未配置时同 minio.endpoint），
 * 并显式指定 region，签名完全在本地计算、不发起任何网络请求。服务端读写一律使用主 {@link MinioClient}。
 */
public record MinioPresignClient(MinioClient client) {
}
