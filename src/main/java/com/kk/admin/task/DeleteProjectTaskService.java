package com.kk.admin.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.project.entity.Project;
import com.kk.project.entity.Submission;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.SubmissionRepository;
import com.kk.oss.OssService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteProjectTaskService {
    private final SubmissionRepository submissionRepository;
    private final ProjectRepository projectRepository;
    private final OssService ossService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformTransactionManager transactionManager;
    private final com.kk.security.repo.ProjectPermissionRepository permRepo;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Data
    public static class Task {
        private String id;
        private Long projectId;
        private String projectName;
        private String actor;
        private String roles;
        private String status; // PENDING, RUNNING, COMPLETED, FAILED
        private int totalFiles;
        private int deletedFiles;
        private String message;
        private long startedAt;
        private Long endedAt;
    }

    public Task get(String id) { return tasks.get(id); }
    public Collection<Task> list() { return tasks.values(); }

    public Task start(Long projectId, Authentication authentication) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Task t = new Task();
        t.setId(UUID.randomUUID().toString());
        t.setProjectId(projectId);
        t.setProjectName(p.getName());
        t.setActor(com.kk.common.logging.AuditLogUtil.actor(authentication));
        t.setRoles(com.kk.common.logging.AuditLogUtil.roles(authentication));
        t.setStatus("PENDING");
        t.setStartedAt(Instant.now().toEpochMilli());
        tasks.put(t.getId(), t);

        log.info("BIZ action=PROJECT_DELETE_TASK_REQUEST taskId={} projectId={} projectName={} actor={} roles={}",
                t.getId(),
                t.getProjectId(),
                com.kk.common.logging.AuditLogUtil.safe(t.getProjectName()),
                t.getActor(),
                t.getRoles());
        executor.submit(() -> runTask(t));
        return t;
    }

    private void runTask(Task t) {
        t.setStatus("RUNNING");
        try {
            log.info("BIZ action=PROJECT_DELETE_TASK_START taskId={} projectId={} projectName={} actor={} roles={}",
                    t.getId(),
                    t.getProjectId(),
                    com.kk.common.logging.AuditLogUtil.safe(t.getProjectName()),
                    t.getActor(),
                    t.getRoles());
            Optional<Project> po = projectRepository.findById(t.getProjectId());
            if (po.isEmpty()) throw new IllegalStateException("Project not found");
            Project p = po.get();
            List<Submission> submissions = submissionRepository.findByProject(p);
            List<String> urls = new ArrayList<>();
            for (Submission s : submissions) {
                try {
                    List<String> u = objectMapper.readValue(
                            s.getFileUrls(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (u != null) urls.addAll(u);
                } catch (Exception ignored) {}
            }
            t.setTotalFiles(urls.size());

            // 分批删除 OSS 对象，更新进度
            int batch = 200;
            int deleted = 0;
            for (int i = 0; i < urls.size(); i += batch) {
                List<String> slice = urls.subList(i, Math.min(urls.size(), i + batch));
                try { ossService.deleteByUrls(slice); } catch (Exception e) {
                    log.warn("Delete OSS slice failed: {}", e.getMessage());
                }
                deleted += slice.size();
                t.setDeletedFiles(deleted);
            }
            // 删除提交与项目（事务内执行，解决 No EntityManager/transaction 问题）
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.execute(status -> {
                Project ref = projectRepository.findById(t.getProjectId()).orElse(null);
                if (ref != null) {
                    // 先删权限，避免外键约束问题
                    permRepo.deleteByProject(ref);
                    submissionRepository.deleteByProject(ref);
                    projectRepository.delete(ref);
                }
                return null;
            });
            t.setStatus("COMPLETED");
            t.setEndedAt(Instant.now().toEpochMilli());
            t.setMessage("ok");
            log.info("BIZ action=PROJECT_DELETE_TASK_DONE taskId={} projectId={} projectName={} totalFiles={} deletedFiles={} actor={}",
                    t.getId(),
                    t.getProjectId(),
                    com.kk.common.logging.AuditLogUtil.safe(t.getProjectName()),
                    t.getTotalFiles(),
                    t.getDeletedFiles(),
                    t.getActor());
        } catch (Exception e) {
            t.setStatus("FAILED");
            t.setEndedAt(Instant.now().toEpochMilli());
            t.setMessage(e.getMessage());
            log.warn("BIZ action=PROJECT_DELETE_TASK_FAILED taskId={} projectId={} projectName={} msg={} actor={}",
                    t.getId(),
                    t.getProjectId(),
                    com.kk.common.logging.AuditLogUtil.safe(t.getProjectName()),
                    com.kk.common.logging.AuditLogUtil.safe(e.getMessage()),
                    t.getActor());
        }
    }
}
