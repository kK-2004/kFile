package com.kk.share;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareCleanupTaskTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private ShareLinkItemRepository shareLinkItemRepository;
    @Mock private CdnPreviewLinkRepository cdnPreviewLinkRepository;

    private ShareCleanupTask task;

    @BeforeEach
    void setUp() {
        task = new ShareCleanupTask(
                shareLinkRepository, shareLinkItemRepository, cdnPreviewLinkRepository);
    }

    @Test
    void removesMissingFileLinksImmediatelyAndExpiredLinksAfterThreeDays() {
        Instant now = Instant.parse("2026-09-24T17:30:00Z");
        Instant cutoff = Instant.parse("2026-09-21T17:30:00Z");
        ShareLink expiredShare = share(1L);
        ShareLink missingFileShare = share(2L);
        CdnPreviewLink expiredCdn = cdn(10L);
        CdnPreviewLink missingFileCdn = cdn(11L);

        when(shareLinkRepository.findByExpireAtBefore(cutoff)).thenReturn(List.of(expiredShare));
        when(shareLinkRepository.findFileSharesWithoutExistingSource())
                .thenReturn(List.of(expiredShare, missingFileShare));
        when(cdnPreviewLinkRepository.findByExpireAtBefore(cutoff)).thenReturn(List.of(expiredCdn));
        when(cdnPreviewLinkRepository.findWithoutExistingFile())
                .thenReturn(List.of(expiredCdn, missingFileCdn));

        ShareCleanupTask.CleanupResult result = task.cleanupAt(now);

        assertThat(result.expiredCutoff()).isEqualTo(cutoff);
        assertThat(result.expiredShareLinks()).isEqualTo(1);
        assertThat(result.deletedFileShareLinks()).isEqualTo(1);
        assertThat(result.expiredCdnLinks()).isEqualTo(1);
        assertThat(result.deletedFileCdnLinks()).isEqualTo(1);
        assertThat(result.totalDeleted()).isEqualTo(4);
        verify(shareLinkItemRepository).deleteByShareLinkIdIn(List.of(1L, 2L));
        verify(shareLinkRepository).deleteAll(List.of(expiredShare, missingFileShare));
        verify(cdnPreviewLinkRepository).deleteAll(List.of(expiredCdn, missingFileCdn));
    }

    @Test
    void doesNotIssueDeletesWhenNothingMatches() {
        Instant now = Instant.parse("2026-09-24T17:30:00Z");
        Instant cutoff = Instant.parse("2026-09-21T17:30:00Z");
        when(shareLinkRepository.findByExpireAtBefore(cutoff)).thenReturn(List.of());
        when(shareLinkRepository.findFileSharesWithoutExistingSource()).thenReturn(List.of());
        when(cdnPreviewLinkRepository.findByExpireAtBefore(cutoff)).thenReturn(List.of());
        when(cdnPreviewLinkRepository.findWithoutExistingFile()).thenReturn(List.of());

        ShareCleanupTask.CleanupResult result = task.cleanupAt(now);

        assertThat(result.totalDeleted()).isZero();
        verify(shareLinkItemRepository, never()).deleteByShareLinkIdIn(org.mockito.ArgumentMatchers.any());
        verify(shareLinkRepository, never()).deleteAll(org.mockito.ArgumentMatchers.any());
        verify(cdnPreviewLinkRepository, never()).deleteAll(org.mockito.ArgumentMatchers.any());
    }

    private static ShareLink share(long id) {
        ShareLink link = new ShareLink();
        link.setId(id);
        return link;
    }

    private static CdnPreviewLink cdn(long id) {
        CdnPreviewLink link = new CdnPreviewLink();
        link.setId(id);
        return link;
    }
}
