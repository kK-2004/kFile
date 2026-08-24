package com.kk.share.controller;

import com.kk.project.repo.ProjectRepository;
import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import com.kk.security.repo.ProjectPermissionRepository;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.share.service.ShareLinkService;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.entity.StoredFile;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import com.kk.storage.repo.StoredFileRepository;
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
    @Mock private Authentication authentication;

    private ShareLinkAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ShareLinkAdminController(
                shareLinkRepository, shareLinkItemRepository, shareLinkService,
                projectRepository, userRepository, permissionRepository,
                cdnPreviewLinkRepository, storedFileRepository);
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
    void adminCanDeleteOwnCdnLink() {
        stubSuperUser();
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(10L);
        link.setCreatedBy(99L);
        when(cdnPreviewLinkRepository.findById(10L)).thenReturn(Optional.of(link));

        assertThat(controller.deleteCdn(10L, authentication)).containsEntry("ok", true);

        verify(cdnPreviewLinkRepository).delete(link);
    }
}
