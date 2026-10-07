package com.kk.share;

import com.kk.share.entity.ShareLink;
import com.kk.share.repo.ShareLinkItemRepository;
import com.kk.share.repo.ShareLinkRepository;
import com.kk.storage.entity.CdnPreviewLink;
import com.kk.storage.repo.CdnPreviewLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareCleanupTask {

    static final Duration EXPIRED_RETENTION = Duration.ofDays(3);

    private final ShareLinkRepository shareLinkRepository;
    private final ShareLinkItemRepository shareLinkItemRepository;
    private final CdnPreviewLinkRepository cdnPreviewLinkRepository;

    @Transactional
    public CleanupResult cleanup() {
        return cleanupAt(Instant.now());
    }

    CleanupResult cleanupAt(Instant now) {
        Instant expiredCutoff = now.minus(EXPIRED_RETENTION);

        List<ShareLink> expiredShares = shareLinkRepository.findByExpireAtBefore(expiredCutoff);
        List<ShareLink> missingFileShares = shareLinkRepository.findFileSharesWithoutExistingSource();
        Map<Long, ShareLink> sharesToDelete = new LinkedHashMap<>();
        expiredShares.forEach(link -> sharesToDelete.put(link.getId(), link));
        missingFileShares.forEach(link -> sharesToDelete.put(link.getId(), link));

        if (!sharesToDelete.isEmpty()) {
            List<Long> ids = List.copyOf(sharesToDelete.keySet());
            shareLinkItemRepository.deleteByShareLinkIdIn(ids);
            shareLinkRepository.deleteAll(List.copyOf(sharesToDelete.values()));
        }

        List<CdnPreviewLink> expiredCdnLinks = cdnPreviewLinkRepository.findByExpireAtBefore(expiredCutoff);
        List<CdnPreviewLink> missingFileCdnLinks = cdnPreviewLinkRepository.findWithoutExistingFile();
        Map<Long, CdnPreviewLink> cdnLinksToDelete = new LinkedHashMap<>();
        expiredCdnLinks.forEach(link -> cdnLinksToDelete.put(link.getId(), link));
        missingFileCdnLinks.forEach(link -> cdnLinksToDelete.put(link.getId(), link));
        if (!cdnLinksToDelete.isEmpty()) {
            cdnPreviewLinkRepository.deleteAll(List.copyOf(cdnLinksToDelete.values()));
        }

        Set<Long> expiredShareIds = idsOfShares(expiredShares);
        Set<Long> expiredCdnIds = idsOfCdnLinks(expiredCdnLinks);
        int deletedFileShares = countNotIn(missingFileShares.stream().map(ShareLink::getId).toList(), expiredShareIds);
        int deletedFileCdnLinks = countNotIn(missingFileCdnLinks.stream().map(CdnPreviewLink::getId).toList(), expiredCdnIds);
        CleanupResult result = new CleanupResult(
                expiredCutoff,
                expiredShareIds.size(),
                deletedFileShares,
                expiredCdnIds.size(),
                deletedFileCdnLinks,
                sharesToDelete.size() + cdnLinksToDelete.size());
        log.info("分享链接清理完成: cutoff={}, expiredShares={}, deletedFileShares={}, "
                        + "expiredCdnLinks={}, deletedFileCdnLinks={}, total={}",
                result.expiredCutoff(), result.expiredShareLinks(), result.deletedFileShareLinks(),
                result.expiredCdnLinks(), result.deletedFileCdnLinks(), result.totalDeleted());
        return result;
    }

    private Set<Long> idsOfShares(List<ShareLink> links) {
        Set<Long> ids = new LinkedHashSet<>();
        links.forEach(link -> ids.add(link.getId()));
        return ids;
    }

    private Set<Long> idsOfCdnLinks(List<CdnPreviewLink> links) {
        Set<Long> ids = new LinkedHashSet<>();
        links.forEach(link -> ids.add(link.getId()));
        return ids;
    }

    private int countNotIn(List<Long> candidateIds, Set<Long> excludedIds) {
        return (int) candidateIds.stream().distinct().filter(id -> !excludedIds.contains(id)).count();
    }

    public record CleanupResult(
            Instant expiredCutoff,
            int expiredShareLinks,
            int deletedFileShareLinks,
            int expiredCdnLinks,
            int deletedFileCdnLinks,
            int totalDeleted) {}
}
