package com.kk.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kk.sdk.ContentCenterClient.DownloadLink;
import com.kk.sdk.ContentCenterClient.DownloadLinkRequest;
import com.kk.sdk.ContentCenterClient.MultipartOptions;
import com.kk.sdk.ContentCenterClient.UploadOptions;
import com.kk.sdk.ContentCenterClient.UploadResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 基于 JDK 内置 HttpServer 的 stub 集成测试：Bearer 注入、简单上传三步、PUT 失败不调 complete、
 * 分片断点续传跳过已传分片、错误解析（400 业务消息 / 401 轮换提示）。
 */
class ContentCenterClientTest {

    private HttpServer server;
    private String base;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final List<String> capturedJson = java.util.Collections.synchronizedList(new ArrayList<>());
    private String capturedAuth;
    private volatile byte[] capturedPutBytes;
    private volatile String capturedPutContentType;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        hits.clear();
        capturedJson.clear();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ContentCenterClient client() {
        return ContentCenterClient.builder().baseUrl(base).appToken("kapp_test").build();
    }

    private void route(String path, Handler handler) {
        server.createContext(path, ex -> {
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            capturedAuth = ex.getRequestHeaders().getFirst("Authorization");
            if (ex.getRequestMethod().equals("POST")) {
                capturedJson.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else if (ex.getRequestMethod().equals("PUT")) {
                capturedPutBytes = ex.getRequestBody().readAllBytes();
                capturedPutContentType = ex.getRequestHeaders().getFirst("Content-Type");
            }
            handler.handle(ex);
        });
    }

    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void injectsBearerHeaderAndUploadsInThreeSteps() throws Exception {
        route("/api/open/uploads", ex -> json(ex, 200,
                "{\"storageKey\":\"k1\",\"source\":\"oss\",\"putUrl\":\"" + base + "/put\",\"expiresIn\":600,\"fileId\":1}"));
        route("/put", ex -> ex.sendResponseHeaders(200, -1));
        route("/api/open/uploads/complete", ex -> json(ex, 200,
                "{\"fileId\":1,\"name\":\"a.txt\",\"size\":3,\"contentType\":\"text/plain\"}"));

        Path tmp = Files.createTempFile("sdk", ".txt");
        Files.writeString(tmp, "abc");
        UploadResult result = client().upload(tmp, UploadOptions.defaults().contentType("text/plain"));

        assertThat(result.fileId()).isEqualTo(1L);
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.storageKey()).isEqualTo("k1");
        assertThat(result.source()).isEqualTo("oss");
        assertThat(capturedAuth).isEqualTo("Bearer kapp_test");
        assertThat(new String(capturedPutBytes, StandardCharsets.UTF_8)).isEqualTo("abc");
        assertThat(capturedPutContentType).isEqualTo("text/plain");
        assertThat(capturedJson.get(0)).contains("\"originalName\":\"" + tmp.getFileName() + "\"");
        assertThat(capturedJson.get(0)).contains("\"contentType\":\"text/plain\"");
        assertThat(capturedJson.get(1)).contains("\"storageKey\":\"k1\"");
        Files.deleteIfExists(tmp);
    }

    @Test
    void putFailureThrowsWithoutCallingComplete() throws Exception {
        route("/api/open/uploads", ex -> json(ex, 200,
                "{\"storageKey\":\"k1\",\"source\":\"oss\",\"putUrl\":\"" + base + "/put-fail\",\"expiresIn\":600,\"fileId\":1}"));
        route("/put-fail", ex -> ex.sendResponseHeaders(403, -1));
        route("/api/open/uploads/complete", ex -> json(ex, 200, "{}"));

        Path tmp = Files.createTempFile("sdk", ".txt");
        Files.writeString(tmp, "abc");
        assertThatThrownBy(() -> client().upload(tmp, UploadOptions.defaults()))
                .isInstanceOf(ContentCenterException.class)
                .hasMessageContaining("直传对象存储失败");
        assertThat(hits.getOrDefault("/api/open/uploads/complete", new AtomicInteger()).get()).isZero();
        Files.deleteIfExists(tmp);
    }

    @Test
    void multipartResumeSkipsUploadedParts() throws Exception {
        int partSize = ContentCenterClient.DEFAULT_PART_SIZE; // 5MB
        Path tmp = Files.createTempFile("sdk", ".bin");
        byte[] data = new byte[partSize + 11];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        Files.write(tmp, data);

        route("/api/open/uploads/multipart/init", ex -> json(ex, 200, "{\"uploadId\":\"u\",\"chunkKeyPrefix\":\"p\","
                + "\"storageKey\":\"sk\",\"totalChunks\":2,\"fileId\":9,"
                + "\"uploadedParts\":[{\"partNumber\":1,\"etag\":\"e1\"}],\"alreadyDone\":false}"));
        route("/api/open/uploads/multipart/sign", ex -> json(ex, 200, "{\"url\":\"" + base + "/part\"}"));
        route("/part", ex -> {
            ex.getResponseHeaders().set("ETag", "\"e2\"");
            ex.sendResponseHeaders(200, -1);
        });
        route("/api/open/uploads/multipart/complete", ex -> json(ex, 200,
                "{\"storageKey\":\"sk\",\"fileId\":9,\"size\":" + data.length + "}"));

        UploadResult result = client().uploadMultipart(tmp, MultipartOptions.defaults().source("minio"));

        assertThat(result.fileId()).isEqualTo(9L);
        assertThat(result.size()).isEqualTo(data.length);
        // 仅上传了缺失的第 2 个分片（长度 11 字节）
        assertThat(hits.get("/part").get()).isEqualTo(1);
        assertThat(capturedPutBytes).hasSize(11);
        assertThat(capturedPutBytes).containsExactly(Arrays.copyOfRange(data, partSize, data.length));
        // complete 提交了两个分片的 ETag：已传 part1 复用 e1，新传 part2 用 e2
        String completeBody = capturedJson.stream()
                .filter(s -> s.contains("\"parts\"")).findFirst().orElse("");
        assertThat(completeBody).contains("\"chunkId\":0").contains("\"etag\":\"e1\"");
        assertThat(completeBody).contains("\"chunkId\":1").contains("\"etag\":\"e2\"");
        // init 携带分片参数
        assertThat(capturedJson.get(0)).contains("\"totalChunks\":2").contains("\"source\":\"minio\"");
        Files.deleteIfExists(tmp);
    }

    @Test
    void parsesApiErrorMessage() {
        route("/api/open/uploads", ex -> json(ex, 400, "{\"message\":\"未知或未启用的数据源: minio\"}"));
        ContentCenterException e = (ContentCenterException) org.assertj.core.api.Assertions.catchThrowable(
                () -> client().upload(java.io.InputStream.nullInputStream(), "a.txt", 1L,
                        UploadOptions.defaults().source("minio")));
        assertThat(e.getStatus()).isEqualTo(400);
        assertThat(e.getMessage()).contains("未知或未启用的数据源: minio");
    }

    @Test
    void unauthorizedHintsTokenRotation() {
        route("/api/open/uploads", ex -> json(ex, 401, "{\"message\":\"无权限，请登录\"}"));
        assertThatThrownBy(() -> client().upload(java.io.InputStream.nullInputStream(), "a.txt", 1L,
                        UploadOptions.defaults()))
                .isInstanceOf(ContentCenterException.class)
                .hasMessageContaining("轮换");
    }

    @Test
    void downloadLinkRequestAndParse() {
        route("/api/open/download-links", ex -> json(ex, 200, "{\"url\":\"https://dl/x?a=1\",\"expiresIn\":300}"));
        DownloadLink link = client().getDownloadLink(
                DownloadLinkRequest.ofFileId(9L).filename("报表.pdf").expiresIn(300));
        assertThat(link.url()).isEqualTo("https://dl/x?a=1");
        assertThat(link.expiresIn()).isEqualTo(300);
        assertThat(capturedJson.get(0)).contains("\"fileId\":9").contains("\"filename\":\"报表.pdf\"");
    }
}
