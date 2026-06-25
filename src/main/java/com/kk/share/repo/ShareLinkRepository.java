package com.kk.share.repo;

import com.kk.share.entity.ShareLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {
    Optional<ShareLink> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShareLink s where s.code = :code")
    Optional<ShareLink> findByCodeForUpdate(@Param("code") String code);

    @Modifying
    @Query("DELETE FROM ShareLink s WHERE s.expireAt IS NOT NULL AND s.expireAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);

    /** 列出过期链接（删除前需先清理子表 share_link_item） */
    @Query("select s from ShareLink s where s.expireAt is not null and s.expireAt < :now")
    java.util.List<ShareLink> findByExpireAtBefore(@Param("now") Instant now);

    /** 列出分享链接（SUPER: projectId 集合为全部；ADMIN: 仅自己有权限的 projectId）。支持按 projectId 过滤。 */
    @Query("select s from ShareLink s where (:projectId is null or s.projectId = :projectId) order by s.createdAt desc")
    Page<ShareLink> findAllByProjectId(@Param("projectId") Long projectId, Pageable pageable);

    /** 按 projectId 列表过滤（ADMIN 只看自己有权限的项目分享） */
    @Query("select s from ShareLink s where s.projectId in :projectIds order by s.createdAt desc")
    Page<ShareLink> findByProjectIds(@Param("projectIds") java.util.Collection<Long> projectIds, Pageable pageable);

    /** 列出所有（SUPER 用），分页 */
    Page<ShareLink> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 删除指定项目下的全部分享链接（项目删除时级联清理） */
    @Modifying
    @Query("DELETE FROM ShareLink s WHERE s.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") Long projectId);

}
