package com.kk.share.repo;

import com.kk.share.entity.ShareLink;
import com.kk.share.entity.ShareLinkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShareLinkItemRepository extends JpaRepository<ShareLinkItem, Long> {

    /** 某分享下的全部分享条目 */
    List<ShareLinkItem> findByShareLink(ShareLink shareLink);

    /** 按分享 id 列出条目，按相对路径排序便于前端层级展示 */
    @Query("select i from ShareLinkItem i where i.shareLink.id = :shareLinkId order by i.relativePath, i.filename, i.id")
    List<ShareLinkItem> findByShareLinkIdOrderByRelativePath(@Param("shareLinkId") Long shareLinkId);

    /** 精确定位条目（用于唯一约束下的查/插幂等） */
    Optional<ShareLinkItem> findByShareLinkIdAndKindAndRefId(Long shareLinkId, String kind, Long refId);

    /** 是否存在指定来源引用的条目 */
    boolean existsByShareLinkIdAndKindAndRefId(Long shareLinkId, String kind, Long refId);

    /** 按来源引用集合删除条目（SUBMISSION_SYNC 物理删除使用） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLinkItem i where i.shareLink.id = :shareLinkId and i.refId in :refIds")
    int deleteByShareLinkIdAndRefIdIn(@Param("shareLinkId") Long shareLinkId, @Param("refIds") Collection<Long> refIds);

    /** 删除某分享链接下的全部分享条目（删除/吊销分享链接前清理子表，避免外键约束失败） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLinkItem i where i.shareLink.id = :shareLinkId")
    int deleteByShareLinkId(@Param("shareLinkId") Long shareLinkId);

    /** 删除指定项目下全部分享链接的条目（通过 join ShareLink.projectId） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLinkItem i where i.shareLink.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") Long projectId);

    /** 批量删除多个分享链接下的全部分享条目（过期清理批量场景） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShareLinkItem i where i.shareLink.id in :shareLinkIds")
    int deleteByShareLinkIdIn(@Param("shareLinkIds") Collection<Long> shareLinkIds);

    /** 按条目 id 自增下载计数 */
    @Modifying
    @Query("update ShareLinkItem i set i.downloadCount = i.downloadCount + 1 where i.id = :id")
    int incrementDownloadCount(@Param("id") Long id);
}
