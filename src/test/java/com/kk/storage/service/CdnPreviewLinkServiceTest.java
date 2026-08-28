package com.kk.storage.service;

import com.kk.storage.StorageBrowserRegistry;
import com.kk.storage.StorageBrowserService;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import com.kk.storage.repo.StoredFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdnPreviewLinkServiceTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private CdnPreviewLinkRepository cdnPreviewLinkRepository;
    @Mock private StorageBrowserRegistry registry;
    @Mock private StorageBrowserService storage;

    private CdnPreviewLinkService service;

    @BeforeEach
    void setUp() {
        service = new CdnPreviewLinkService(storedFileRepository, cdnPreviewLinkRepository, registry);
    }

    @Test
    void rejectsNonMediaFiles() {
        StoredFile file = file("application/zip", "archive.zip");
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.create(1L, 0L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持图片、音频和视频");
    }

    @Test
    void createsPermanentLinkForMediaFile() {
        StoredFile file = file("image/png", "cover.png");
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(cdnPreviewLinkRepository.save(any(CdnPreviewLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CdnPreviewLinkService.CreatedLink result = service.create(1L, 0L, 7L);

        assertThat(result.token()).hasSize(43);
        assertThat(result.expireAt()).isNull();
        ArgumentCaptor<CdnPreviewLink> captor = ArgumentCaptor.forClass(CdnPreviewLink.class);
        verify(cdnPreviewLinkRepository).save(captor.capture());
        assertThat(captor.getValue().getStoredFileId()).isEqualTo(1L);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(7L);
        assertThat(captor.getValue().getExpireAt()).isNull();
    }

    @Test
    void createsOpenAppLinkOnlyForOwnedMediaFile() {
        StoredFile file = file("image/png", "cover.png");
        file.setOpenAppId(7L);
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(cdnPreviewLinkRepository.save(any(CdnPreviewLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CdnPreviewLinkService.CreatedLink result = service.createForOpenApp(1L, 600L, 7L);

        assertThat(result.token()).hasSize(43);
        assertThat(result.expireAt()).isAfter(Instant.now());
        assertThat(result.contentType()).isEqualTo("image/png");
    }

    @Test
    void rejectsOpenAppLinkForAnotherAppsFile() {
        StoredFile file = file("audio/mpeg", "song.mp3");
        file.setOpenAppId(8L);
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.createForOpenApp(1L, 0L, 7L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(e -> ((org.springframework.web.server.ResponseStatusException) e).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsExpiredLinkBeforeGeneratingStorageUrl() {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setToken("expired-token");
        link.setStoredFileId(1L);
        link.setExpireAt(Instant.now().minusSeconds(1));
        when(cdnPreviewLinkRepository.findByToken("expired-token")).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.previewUrl("expired-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("链接已过期");
    }

    @Test
    void renewsLinkFromNowWithSelectedDuration() {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(9L);
        link.setStoredFileId(1L);
        link.setCreatedBy(7L);
        link.setExpireAt(Instant.now().minusSeconds(1));
        StoredFile file = file("image/png", "cover.png");
        when(cdnPreviewLinkRepository.findById(9L)).thenReturn(Optional.of(link));
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(cdnPreviewLinkRepository.save(link)).thenReturn(link);

        Instant before = Instant.now().plusSeconds(3599);
        CdnPreviewLink result = service.renew(9L, 3600L, 7L);

        assertThat(result.getExpireAt()).isAfter(before);
        assertThat(result.getExpireAt()).isBefore(Instant.now().plusSeconds(3601));
    }

    @Test
    void renewsLinkAsPermanentWhenDurationIsZero() {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(9L);
        link.setStoredFileId(1L);
        link.setCreatedBy(7L);
        link.setExpireAt(Instant.now().plusSeconds(60));
        when(cdnPreviewLinkRepository.findById(9L)).thenReturn(Optional.of(link));
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file("audio/mpeg", "song.mp3")));
        when(cdnPreviewLinkRepository.save(link)).thenReturn(link);

        assertThat(service.renew(9L, 0L, 7L).getExpireAt()).isNull();
    }

    @Test
    void rejectsRenewalByAnotherUser() {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(9L);
        link.setStoredFileId(1L);
        link.setCreatedBy(7L);
        when(cdnPreviewLinkRepository.findById(9L)).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.renew(9L, 3600L, 8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void generatesInlineMediaUrlFromStableLink() {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setToken("media-token");
        link.setStoredFileId(1L);
        StoredFile file = file("video/mp4", "clip.mp4");
        when(cdnPreviewLinkRepository.findByToken("media-token")).thenReturn(Optional.of(link));
        when(storedFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(registry.get("minio")).thenReturn(storage);
        when(storage.previewUrl("objects/clip.mp4", 300L, "clip.mp4", "video/mp4"))
                .thenReturn("https://minio.example/objects/clip.mp4?signed");

        assertThat(service.previewUrl("media-token"))
                .isEqualTo("https://minio.example/objects/clip.mp4?signed");
        verify(storage).previewUrl("objects/clip.mp4", 300L, "clip.mp4", "video/mp4");
    }

    private static StoredFile file(String contentType, String name) {
        StoredFile file = new StoredFile();
        file.setId(1L);
        file.setUploaderId(7L);
        file.setType(StoredFile.TYPE_FILE);
        file.setStatus(StoredFile.STATUS_UPLOADED);
        file.setName(name);
        file.setOriginalName(name);
        file.setContentType(contentType);
        file.setStorageSource("minio");
        file.setStorageKey("objects/" + name);
        return file;
    }
}
