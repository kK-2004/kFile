package com.kk.storage.service;

import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import com.kk.storage.repo.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/** Creates stable public links which resolve to inline media URLs. */
@Service
@RequiredArgsConstructor
public class CdnPreviewLinkService {

    private static final long SIGNED_URL_EXPIRE_SECONDS = 300L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StoredFileRepository storedFileRepository;
    private final CdnPreviewLinkRepository cdnPreviewLinkRepository;
    private final StorageBrowserRegistry registry;

    @Transactional
    public CreatedLink create(Long fileId, Long expireSeconds, Long actorId) {
        StoredFile file = findPreviewableFile(fileId);
        checkOwner(file, actorId);

        return createLink(file, expireSeconds, actorId);
    }

    /** Creates a stable preview link for a file owned by an open app. */
    @Transactional
    public CreatedLink createForOpenApp(Long fileId, Long expireSeconds, Long openAppId) {
        if (fileId == null) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        StoredFile file = storedFileRepository.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        // 越权和不存在统一返回 404，避免通过 fileId 探测其他应用的文件。
        if (!StoredFile.TYPE_FILE.equals(file.getType())
                || StoredFile.STATUS_UPLOADING.equals(file.getStatus())
                || openAppId == null
                || !openAppId.equals(file.getOpenAppId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }

        return createLink(file, expireSeconds, null);
    }

    private CreatedLink createLink(StoredFile file, Long expireSeconds, Long actorId) {
        String contentType = mediaType(file);

        long seconds = expireSeconds == null ? 0L : expireSeconds;
        if (seconds < 0L) {
            throw new IllegalArgumentException("有效期不能为负数");
        }

        CdnPreviewLink link = new CdnPreviewLink();
        link.setToken(newToken());
        link.setStoredFileId(file.getId());
        link.setCreatedBy(actorId);
        link.setCreatedAt(Instant.now());
        link.setExpireAt(seconds == 0L ? null : Instant.now().plusSeconds(seconds));
        cdnPreviewLinkRepository.save(link);
        return new CreatedLink(link.getToken(), link.getExpireAt(), contentType);
    }

    @Transactional(readOnly = true)
    public String previewUrl(String token) {
        CdnPreviewLink link = cdnPreviewLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("CDN 链接不存在"));
        if (link.getExpireAt() != null && !link.getExpireAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("CDN 链接已过期");
        }

        StoredFile file = findPreviewableFile(link.getStoredFileId());
        String contentType = mediaType(file);
        StorageBrowserService storage = registry.get(file.getStorageSource());
        return storage.previewUrl(
                file.getStorageKey(),
                SIGNED_URL_EXPIRE_SECONDS,
                displayName(file),
                contentType);
    }

    private StoredFile findPreviewableFile(Long fileId) {
        StoredFile file = storedFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
        if (!StoredFile.TYPE_FILE.equals(file.getType())) {
            throw new IllegalArgumentException("文件夹不支持 CDN 预览");
        }
        if (StoredFile.STATUS_UPLOADING.equals(file.getStatus())) {
            throw new IllegalArgumentException("文件仍在上传中，暂不支持 CDN 预览");
        }
        mediaType(file);
        return file;
    }

    private void checkOwner(StoredFile file, Long actorId) {
        if (actorId != null && file.getUploaderId() != null && !actorId.equals(file.getUploaderId())) {
            throw new IllegalArgumentException("无权生成该文件的 CDN 链接");
        }
    }

    private String mediaType(StoredFile file) {
        String contentType = file.getContentType();
        if (!isMedia(contentType)) {
            contentType = MediaTypeFactory.getMediaType(displayName(file))
                    .map(MediaType::toString)
                    .orElse(contentType);
        }
        if (!isMedia(contentType)) {
            throw new IllegalArgumentException("仅支持图片、音频和视频文件生成 CDN 链接");
        }
        return contentType;
    }

    private String displayName(StoredFile file) {
        if (StringUtils.hasText(file.getOriginalName())) return file.getOriginalName();
        return StringUtils.hasText(file.getName()) ? file.getName() : "media";
    }

    private boolean isMedia(String contentType) {
        if (!StringUtils.hasText(contentType)) return false;
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("image/")
                || normalized.startsWith("audio/")
                || normalized.startsWith("video/");
    }

    private String newToken() {
        String token;
        do {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (cdnPreviewLinkRepository.existsByToken(token));
        return token;
    }

    public record CreatedLink(String token, Instant expireAt, String contentType) {}
}
