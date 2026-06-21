package com.kk.storage.repo;

import com.kk.storage.entity.StoredFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    /** 列出某父目录的直接子项（按 uploaderId 过滤，parentId 为 null 时列根级），分页；文件夹优先、再按名称；可选关键词 */
    @org.springframework.data.jpa.repository.Query("""
        select f from StoredFile f
        where (:uploaderId is null or f.uploaderId = :uploaderId)
          and ((:parentId is null and f.parentId is null) or f.parentId = :parentId)
          and (:kw is null or lower(f.name) like lower(concat('%', :kw, '%')))
        order by case f.type when 'FOLDER' then 0 else 1 end, lower(f.name), f.id
        """)
    org.springframework.data.domain.Page<StoredFile> listChildren(
            @org.springframework.data.repository.query.Param("parentId") Long parentId,
            @org.springframework.data.repository.query.Param("uploaderId") Long uploaderId,
            @org.springframework.data.repository.query.Param("kw") String keyword,
            org.springframework.data.domain.Pageable pageable);

    Optional<StoredFile> findByParentIdIsNullAndNameAndType(String name, String type);

    Optional<StoredFile> findByParentIdAndNameAndType(Long parentId, String name, String type);

    List<StoredFile> findByParentId(Long parentId);

    /** 统计某上传者的所有 FILE 总字节数（配额核算） */
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(f.size),0) from StoredFile f where f.uploaderId = :uploaderId and f.type = 'FILE'")
    long sumSizeByUploader(@org.springframework.data.repository.query.Param("uploaderId") Long uploaderId);

    /** 查某上传者的 UPLOADING 状态文件（未完成上传） */
    List<StoredFile> findByUploaderIdAndStatus(Long uploaderId, String status);

    /** 按 storageKey + UPLOADING 查预创建的记录 */
    Optional<StoredFile> findByStorageKeyAndStatus(String storageKey, String status);
}
