package com.kk.share.controller;

import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import com.kk.storage.repo.StoredFileRepository;
import com.kk.storage.service.CdnPreviewLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareLinkAdminControllerTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private ShareLinkItemRepository shareLinkItemRepository;
    @Mock private ShareLinkService shareLinkService;
    @Mock private ProjectRepository projectRepository;
    @Mock private AdminUserRepository userRepository;
    @Mock private ProjectPermissionRepository permissionRepository;
    @Mock private CdnPreviewLinkRepository cdnPreviewLinkRepository;
    @Mock private StoredFileRepository storedFileRepository;
    @Mock private CdnPreviewLinkService cdnPreviewLinkService;
    @Mock private Authentication authentication;

    private ShareLinkAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ShareLinkAdminController(
                shareLinkRepository, shareLinkItemRepository, shareLinkService,
                projectRepository, userRepository, permissionRepository,
                cdnPreviewLinkRepository, storedFileRepository, cdnPreviewLinkService);
        when(authentication.getName()).thenReturn("super");
    }

    private void stubSuperUser() {
        AdminUser user = new AdminUser();
        user.setId(99L);
        user.setUsername("super");
        user.setRole("SUPER");
        when(userRepository.findByUsername("super")).thenReturn(Optional.of(user));
    }

    @Test
    void listsCdnLinksAsCdnRows() {
        stubSuperUser();
        when(projectRepository.findAll()).thenReturn(List.of());
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(10L);
        link.setToken("cdn-token");
        link.setStoredFileId(116L);
        link.setCreatedAt(Instant.parse("2026-08-25T00:00:00Z"));
        StoredFile file = new StoredFile();
        file.setOriginalName("cover.png");
        file.setName("cover.png");
        when(shareLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(cdnPreviewLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(link));
        when(storedFileRepository.findById(116L)).thenReturn(Optional.of(file));

        Map<String, Object> response = controller.list(0, 15, null, "CDN", authentication);

        assertThat(response.get("total")).isEqualTo(1);
        List<?> nodes = (List<?>) response.get("nodes");
        assertThat(nodes).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) nodes.get(0);
        assertThat(row.get("shareType")).isEqualTo("CDN");
        assertThat(row.get("code")).isEqualTo("cdn-token");
        assertThat(row.get("filename")).isEqualTo("cover.png");
    }

    @Test
    void listsRootShareWithFileManagementLocation() {
        stubSuperUser();
        when(projectRepository.findAll()).thenReturn(List.of());

        ShareLink link = new ShareLink();
        link.setId(20L);
        link.setCode("root-share");
        link.setShareType(ShareLink.SHARE_TYPE_FILE_SET);
        link.setCreatedAt(Instant.parse("2026-08-25T00:00:00Z"));

        ShareLinkItem item = item(201L, "root.txt");
        when(shareLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(link));
        when(shareLinkItemRepository.findByShareLinkIdOrderByRelativePath(20L)).thenReturn(List.of(item));
        when(storedFileRepository.findAllById(List.of(201L))).thenReturn(List.of(file(201L, null, "root.txt")));
        when(cdnPreviewLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> response = controller.list(0, 15, null, "ALL", authentication);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) response.get("nodes")).get(0);
        assertThat(row.get("locationText")).isEqualTo("文件管理");
    }

    @Test
    void listsDistinctParentFolderNamesInShareLocation() {
        stubSuperUser();
        when(projectRepository.findAll()).thenReturn(List.of());

        ShareLink link = new ShareLink();
        link.setId(21L);
        link.setCode("multi-parent-share");
        link.setShareType(ShareLink.SHARE_TYPE_FILE_SET);
        link.setCreatedAt(Instant.parse("2026-08-25T00:00:00Z"));

        List<ShareLinkItem> items = List.of(
                item(211L, "a.png"), item(212L, "b.mp3"), item(213L, "c.png"));
        when(shareLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(link));
        when(shareLinkItemRepository.findByShareLinkIdOrderByRelativePath(21L)).thenReturn(items);
        when(storedFileRepository.findAllById(List.of(211L, 212L, 213L))).thenReturn(List.of(
                file(211L, 301L, "a.png"), file(212L, 302L, "b.mp3"), file(213L, 301L, "c.png")));
        when(storedFileRepository.findAllById(List.of(301L, 302L))).thenReturn(List.of(
                file(301L, null, "图片"), file(302L, null, "音频")));
        when(cdnPreviewLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> response = controller.list(0, 15, null, "ALL", authentication);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) response.get("nodes")).get(0);
        assertThat(row.get("locationText")).isEqualTo("图片、音频");
    }

    @Test
    void folderShareUsesSelectedFolderParentOnly() {
        stubSuperUser();
        when(projectRepository.findAll()).thenReturn(List.of());

        ShareLink link = new ShareLink();
        link.setId(22L);
        link.setCode("folder-share");
        link.setShareType(ShareLink.SHARE_TYPE_FOLDER_SYNC);
        link.setCreatedAt(Instant.parse("2026-08-25T00:00:00Z"));

        ShareLinkItem selectedFolder = item(221L, "资料");
        selectedFolder.setKind(ShareLinkItem.KIND_FOLDER);
        selectedFolder.setRelativePath("");
        ShareLinkItem derivedFile = item(222L, "nested.txt");
        derivedFile.setRelativePath("资料");
        when(shareLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(link));
        when(shareLinkItemRepository.findByShareLinkIdOrderByRelativePath(22L))
                .thenReturn(List.of(selectedFolder, derivedFile));
        when(storedFileRepository.findAllById(List.of(221L))).thenReturn(List.of(
                file(221L, 301L, "资料")));
        when(storedFileRepository.findAllById(List.of(301L))).thenReturn(List.of(
                file(301L, null, "图片")));
        when(cdnPreviewLinkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> response = controller.list(0, 15, null, "ALL", authentication);

        Map<?, ?> row = (Map<?, ?>) ((List<?>) response.get("nodes")).get(0);
        assertThat(row.get("locationText")).isEqualTo("图片");
    }

    @Test
    void adminCanDeleteOwnCdnLink() {
        stubSuperUser();
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(10L);
        link.setCreatedBy(99L);
        when(cdnPreviewLinkRepository.findById(10L)).thenReturn(Optional.of(link));

        assertThat(controller.deleteCdn(10L, authentication)).containsEntry("ok", true);

        verify(cdnPreviewLinkRepository).delete(link);
    }

    @Test
    void renewsCdnLinkWithSelectedExpiry() {
        stubSuperUser();
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(10L);
        link.setExpireAt(Instant.parse("2026-08-25T00:00:00Z"));
        when(cdnPreviewLinkService.renew(10L, 3600L, null)).thenReturn(link);

        Map<String, Object> response = controller.renewCdn(
                10L, new ShareLinkAdminController.CdnExpiryRequest(3600L), authentication);

        assertThat(response.get("ok")).isEqualTo(true);
        assertThat(response.get("permanent")).isEqualTo(false);
        assertThat(response.get("expireAt")).isEqualTo(link.getExpireAt());
        verify(cdnPreviewLinkService).renew(10L, 3600L, null);
    }

    private static ShareLinkItem item(Long refId, String filename) {
        ShareLinkItem item = new ShareLinkItem();
        item.setRefId(refId);
        item.setKind(ShareLinkItem.KIND_FILE);
        item.setFilename(filename);
        return item;
    }

    private static StoredFile file(Long id, Long parentId, String name) {
        StoredFile file = new StoredFile();
        file.setId(id);
        file.setParentId(parentId);
        file.setType(StoredFile.TYPE_FILE);
        file.setName(name);
        file.setOriginalName(name);
        return file;
    }
}
