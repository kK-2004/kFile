package com.kk.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.common.service.AppConfigService;
import com.kk.config.KMessageProperties;
import com.kk.project.entity.Project;
import com.kk.project.entity.XxlJobRef;
import com.kk.project.repo.ProjectRepository;
import com.kk.project.repo.XxlJobRefRepository;
import com.kk2004.kmessage.sdk.KMessageClient;
import com.kk2004.kmessage.sdk.KMessageClient.CardMessage;
import com.kk.xxljob.XxlJobAdminClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目截止提醒调度编排。
 *
 * 启用条件：app.kmessage.enabled=true 且 xxl.job.enabled=true。
 * 任一未启用时，整个 Bean 不装配，{@link ProjectService} 注入为 null，调用全部短路（见接入点判空）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kmessage.enabled", havingValue = "true")
public class ProjectDeadlineReminderService {

    /** XxlJob handler 名 */
    public static final String HANDLER = "projectDeadlineRemindJob";

    /** 任务描述（admin 后台展示用），由业务层决定前缀，避免污染通用 client */
    private static final String JOB_DESC = "deadline-remind-" + HANDLER;

    /** 提前小时数允许范围：≥1，≤720（30 天） */
    public static final int NOTIFY_HOURS_MIN = 1;
    public static final int NOTIFY_HOURS_MAX = 720;

    /**
     * cron 表达式所用时区：必须与 xxl-job admin 进程的 JVM 时区一致。
     * 本项目 DB 时间统一存 UTC（Instant），而 admin 部署在 UTC+8（Asia/Shanghai），
     * 故 toCron 必须按 +8 拆出「墙上时间」数字，admin 解析时才能还原出正确触发瞬间。
     * 这不是可配项 —— 一旦和 admin 部署脱钩，cron 会整体偏移 8 小时。
     */
    private static final ZoneId CRON_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(DISPLAY_ZONE);
    private static final String CARD_TEMPLATE_VERSION = "v3";

    private final ProjectRepository projectRepository;
    private final XxlJobRefRepository xxlJobRefRepository;
    private final AppConfigService appConfigService;
    private final KMessageProperties kMessageProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 仅在 xxl.job 启用时才装配，故用 Object 容器接收，按需强转 */
    @Autowired(required = false)
    private XxlJobAdminClient xxlJobAdminClient;

    /** 仅在 app.kmessage.enabled=true 时装配 */
    @Autowired(required = false)
    private KMessageClient kMessageClient;

    /**
     * 未提交名单查询。用 @Lazy 打破循环依赖：
     * ProjectService → ReminderService → ProjectQueryService → ProjectService。
     * Lazy 保证仅在真正调用 sendReminder 时才解析代理，启动期不触发循环。
     */
    @Autowired(required = false)
    @Lazy
    private ProjectQueryService projectQueryService;

    /** 站点公网地址，用于在提醒卡片里拼提交链接；未配则只显示路径 */
    @Value("${app.public-base-url:${env.cors:}}")
    private String publicBaseUrl;

    /**
     * 为项目创建/更新一次性提醒任务（endAt-12h）。
     * 任何异常一律 log.warn 不抛，保证不影响主业务。
     */
    public void scheduleFor(Project project) {
        if (xxlJobAdminClient == null) {
            log.debug("xxl-job 未启用，跳过截止提醒调度 projectId={}", project.getId());
            return;
        }
        if (project.getEndAt() == null) {
            // endAt 被清空：取消已有任务
            cancelFor(project);
            return;
        }
        int hours = normalizeHours(project.getDeadlineNotifyHours());
        try {
            Instant triggerAt = project.getEndAt().minusSeconds(hours * 3600L);
            if (!triggerAt.isAfter(Instant.now())) {
                // 触发时间已过：删除可能存在的旧任务，避免挂一个永不触发的废任务
                log.info("deadline remind trigger time already passed, skip schedule projectId={} hours={} triggerAt={}",
                        project.getId(), hours, triggerAt);
                cancelFor(project);
                return;
            }
            String cron = toCron(triggerAt);
            String param = paramOf(project.getId(), hours);
            Optional<XxlJobRef> existing = xxlJobRefRepository.findByProjectId(project.getId());
            if (existing.isPresent()) {
                XxlJobRef ref = existing.get();
                boolean hoursChanged = ref.getNotifyHours() == null || ref.getNotifyHours() != hours;
                boolean cronChanged = !cron.equals(ref.getCron());
                if (!hoursChanged && !cronChanged) {
                    log.debug("deadline remind unchanged, skip update projectId={}", project.getId());
                    return;
                }
                if (ref.getJobId() != null) {
                    try {
                        xxlJobAdminClient.updateJob(ref.getJobId(), HANDLER, param, cron, JOB_DESC);
                    } catch (Exception e) {
                        // update 失败则尝试删旧重建
                        log.warn("deadline remind update failed, fallback to remove+add projectId={} reason={}",
                                project.getId(), e.getMessage());
                        safeRemove(ref.getJobId());
                        Long newId = xxlJobAdminClient.addJob(HANDLER, param, cron, JOB_DESC);
                        ref.setJobId(newId);
                    }
                } else {
                    Long newId = xxlJobAdminClient.addJob(HANDLER, param, cron, JOB_DESC);
                    ref.setJobId(newId);
                }
                ref.setCron(cron);
                ref.setNotifyHours(hours);
                xxlJobRefRepository.save(ref);
                log.info("deadline remind updated projectId={} jobId={} hours={} cron={}",
                        project.getId(), ref.getJobId(), hours, cron);
            } else {
                long jobId = xxlJobAdminClient.addJob(HANDLER, param, cron, JOB_DESC);
                XxlJobRef ref = new XxlJobRef();
                ref.setProjectId(project.getId());
                ref.setJobId(jobId);
                ref.setHandler(HANDLER);
                ref.setCron(cron);
                ref.setNotifyHours(hours);
                xxlJobRefRepository.save(ref);
                log.info("deadline remind created projectId={} jobId={} hours={} cron={}",
                        project.getId(), jobId, hours, cron);
            }
        } catch (Exception e) {
            log.warn("schedule deadline remind failed projectId={} reason={}", project.getId(), e.getMessage());
        }
    }

    /** 规范化提前小时数：null 或越界回退到 12。 */
    private static int normalizeHours(Integer raw) {
        if (raw == null || raw < NOTIFY_HOURS_MIN || raw > NOTIFY_HOURS_MAX) return 12;
        return raw;
    }

    /**
     * 取消项目的提醒任务（删除项目或清空 endAt 时调用）。
     *
     * 注意：不标注 @Transactional。该方法含 HTTP 远程调用（safeRemove → xxl-job admin），
     * 包在 DB 事务里会长时间占用连接；且 scheduleFor 的自调用路径会绕过代理使事务语义不一致。
     * 各 JPA repo 方法自带 per-op 事务，对这里的单条 delete 足够。
     */
    public void cancelFor(Project project) {
        if (xxlJobAdminClient == null) return;
        try {
            xxlJobRefRepository.findByProjectId(project.getId()).ifPresent(ref -> {
                if (ref.getJobId() != null) safeRemove(ref.getJobId());
                xxlJobRefRepository.delete(ref);
                log.info("deadline remind cancelled projectId={} jobId={}", project.getId(), ref.getJobId());
            });
        } catch (Exception e) {
            log.warn("cancel deadline remind failed projectId={} reason={}", project.getId(), e.getMessage());
        }
    }

    /**
     * 实际发送提醒（由 XxlJob handler 触发）。
     * 二次校验 endAt，避免项目变更后旧任务仍发消息。
     */
    public void sendReminder(Long projectId) {
        if (kMessageClient == null) {
            log.debug("kmessage 未启用，跳过发送 projectId={}", projectId);
            return;
        }
        Project p = projectRepository.findById(projectId).orElse(null);
        if (p == null || p.getEndAt() == null) {
            log.info("deadline remind skipped: project missing or no endAt projectId={}", projectId);
            return;
        }
        // 提前小时数：优先取 ref 里记录的（与建任务时一致），否则回退到项目当前值
        int hours = xxlJobRefRepository.findByProjectId(projectId)
                .map(XxlJobRef::getNotifyHours)
                .filter(h -> h != null && h >= NOTIFY_HOURS_MIN && h <= NOTIFY_HOURS_MAX)
                .orElseGet(() -> normalizeHours(p.getDeadlineNotifyHours()));
        // 进入 N 小时窗口（含允许的调度误差 5min）才发；否则视为旧任务误触发
        Instant now = Instant.now();
        Instant windowStart = p.getEndAt().minusSeconds(hours * 3600L + 5 * 60L);
        if (now.isBefore(windowStart)) {
            log.info("deadline remind skipped: not in {}h window yet projectId={} now={} windowStart={}",
                    hours, projectId, now, windowStart);
            return;
        }
        String groupId = resolveGroupId();
        if (groupId == null) {
            log.warn("deadline remind skipped: groupId 未配置（请在 SUPER 后台配置 kMessage 接收群）projectId={}", projectId);
            return;
        }
        Map<String, Object> card = buildFeishuCard(p, now, hours);
        try {
            kMessageClient.sendToGroup(groupId, CardMessage.of(card), idempotencyKey(projectId, hours, p.getEndAt()));
            log.info("deadline remind sent projectId={} groupId={} hours={}", projectId, groupId, hours);
        } catch (Exception e) {
            // 让 handler 看到失败以便 xxl-job 失败重试机制兜底
            throw new RuntimeException("发送截止提醒失败: " + e.getMessage(), e);
        }
    }

    private void safeRemove(Long jobId) {
        try {
            xxlJobAdminClient.removeJob(jobId);
        } catch (Exception e) {
            log.warn("remove xxl-job task failed jobId={} reason={}", jobId, e.getMessage());
        }
    }

    /** groupId：仅从 SUPER 后台 config 读取。channelInstanceId 由 kmessage-sdk 根据 groupId 内部解析。 */
    private String resolveGroupId() {
        return appConfigService.getRaw(AppConfigService.KEY_KMESSAGE_GROUP_ID);
    }

    /** handler 参数：deadline-{hours}h-{projectId} */
    public static String paramOf(Long projectId, int hours) {
        return "deadline-" + hours + "h-" + projectId;
    }

    /**
     * kMessage 幂等 key：project-{projectId}-{hours}h-{endAtEpoch}-{cardTemplateVersion}
     *
     * key 必须能唯一标识"针对哪一次截止时刻发的提醒"，否则会出现两种 bug：
     *  - endAt 变了但 key 不变 → 新提醒被当重复消息丢弃
     *  - 同一项目重复触发但 key 变了 → 失去去重保护，重复发送
     *
     * 因此 key 含项目 id、提前量、目标截止时刻（epoch 秒）和卡片模板版本。
     *  - endAt 变 → key 变 → 新消息能发
     *  - hours 变 → key 变 → 新消息能发
     *  - 卡片模板修复 → key 变 → 不会被旧失败消息的 requestHash 卡住
     *  - 两者都不变（重复触发/重试）→ key 不变 → 去重
     */
    public static String idempotencyKey(Long projectId, int hours, Instant endAt) {
        long endAtSec = endAt == null ? 0L : endAt.getEpochSecond();
        return "project-" + projectId + "-" + hours + "h-" + endAtSec + "-" + CARD_TEMPLATE_VERSION;
    }

    /** 从 param 中解析 projectId（格式 deadline-{hours}h-{projectId}） */
    public static Long parseProjectId(String param) {
        if (param == null) return null;
        // 兼容两种格式：deadline-{hours}h-{id} 与 历史 deadline-12h-{id}
        int h = param.indexOf("h-");
        if (h <= 0) return null;
        String s = param.substring(h + 2);
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把触发时刻转成 6 段 Quartz cron：秒 分 时 日 月 周 年。
     *
     * 时区处理（关键）：triggerAt 是 UTC 的绝对时刻（Instant），但 xxl-job admin 的
     * Quartz 用其进程 JVM 时区（Asia/Shanghai, UTC+8）解析 cron 中的数字。所以必须把
     * triggerAt 转成 +8 的「墙上时间」再填入 cron，admin 解析时才能还原出同一个绝对瞬间。
     * 若填 UTC 数字，admin 会当成 +8 → 触发时刻整体晚 8 小时。
     *
     * 该 cron 在指定那一刻触发一次后即作废（带年份，不会下一年重复匹配）。
     */
    private String toCron(Instant triggerAt) {
        ZonedDateTime t = triggerAt.atZone(CRON_ZONE);
        return String.format("%d %d %d %d %d ? %d",
                t.getSecond(),
                t.getMinute(),
                t.getHour(),
                t.getDayOfMonth(),
                t.getMonthValue(),
                t.getYear());
    }

    /**
     * 构造飞书 interactive 卡片（JSON 1.0 结构）。
     *
     * 选择 1.0 而非 2.0 的原因：2.0 的 collapsible_panel 在实际发送时渲染异常，
     * 而名单已改为正文展示，不需要 2.0 专属组件。1.0 结构简单稳定，div/hr/note/lark_md
     * 均为兼容组件，跨客户端表现一致。
     */
    private Map<String, Object> buildFeishuCard(Project p, Instant now, int hours) {
        Map<String, Object> card = new LinkedHashMap<>();
        MissingData missingData = queryMissing(p);

        // ===== config（1.0）=====
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);
        config.put("enable_forward", true);
        card.put("config", config);

        // ===== header（1.0：template 放 header 下，title 用 tag=plain_text）=====
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "plain_text");
        title.put("content", cardTitle(p, now, hours, missingData));
        header.put("title", title);
        header.put("template", "orange");
        card.put("header", header);

        // ===== elements（1.0：直接挂卡片根）=====
        String endAtText = formatEndAt(p);
        String remainText = formatRemain(p, now);

        StringBuilder md = new StringBuilder();
        md.append("**项目名称：** ").append(escape(p.getName())).append("\n");
        md.append("**项目 ID：** ").append(p.getId()).append("\n");
        md.append("**截止时间：** ").append(endAtText).append("（北京时间）\n");
        md.append("**剩余时间：** ").append(remainText).append("\n");
        if (p.getTotalSubmitters() != null) {
            md.append("**已收集提交：** ").append(p.getTotalSubmitters()).append(" 份\n");
        }
        md.append("**提交链接：** ").append(submitLink(p.getId()));

        List<Object> elements = new ArrayList<>();
        elements.add(larkMdDiv(md.toString()));
        elements.add(Map.of("tag", "hr"));

        // 未提交名单直接正文展示（仅对配置名单的项目显示）
        Map<String, Object> panel = buildMissingSummaryBlock(missingData);
        if (panel != null) {
            elements.add(panel);
            elements.add(Map.of("tag", "hr"));
        }

        // note
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("tag", "note");
        Map<String, Object> noteText = new LinkedHashMap<>();
        noteText.put("tag", "plain_text");
        noteText.put("content", "此消息由 k-File 在截止前 " + hours + " 小时自动提醒");
        note.put("elements", List.of(noteText));
        elements.add(note);

        card.put("elements", elements);
        return card;
    }

    private String cardTitle(Project p, Instant now, int hours, MissingData missingData) {
        List<String> parts = new ArrayList<>();
        parts.add("⏰ 项目将在 " + hours + " 小时后截止");
        return String.join(" ｜ ", parts);
    }

    private static String formatEndAt(Project p) {
        return p.getEndAt() == null ? "未设置" : DISPLAY_TIME_FORMAT.format(p.getEndAt());
    }

    /**
     * 构造提交链接的 lark_md 片段：[用户提交页](绝对URL)。
     * publicBaseUrl 未配时退化为纯路径（飞书会把它渲染为不可点的文本）。
     */
    private String submitLink(Long projectId) {
        String path = "/user/projects/" + projectId;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return "[" + (base.isEmpty() ? path : base + path) + "](" + (base.isEmpty() ? path : base + path) + ")";
    }

    private static String formatRemain(Project p, Instant now) {
        long remainMin = p.getEndAt() == null
                ? 0
                : Math.max(0L, (p.getEndAt().toEpochMilli() - now.toEpochMilli()) / 60000L);
        return String.format("%d 小时 %d 分钟", remainMin / 60, remainMin % 60);
    }

    private static String truncate(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) return s == null ? "" : s;
        return s.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private Map<String, Object> buildMissingSummaryBlock(MissingData missing) {
        if (missing == null) return null;
        StringBuilder md = new StringBuilder();
        md.append("**未提交名单（").append(missing.missingCount()).append(" 人）：**\n");
        List<String> keys = missing.keys();
        boolean composite = keys.size() > 1;
        List<Map<String, String>> list = missing.missing();
        for (int i = 0; i < list.size(); i++) {
            Map<String, String> row = list.get(i);
            if (!composite) {
                String k = keys.get(0);
                md.append(i + 1).append(". ").append(escape(row.getOrDefault(k, "")));
            } else {
                List<String> parts = new ArrayList<>();
                for (String k : keys) {
                    parts.add(escape(k) + ": " + escape(row.getOrDefault(k, "")));
                }
                md.append(i + 1).append(". ").append(String.join("  ", parts));
            }
            if (i < list.size() - 1) md.append("\n");
        }
        return larkMdDiv(md.toString());
    }

    /**
     * 查询未提交名单。直接在卡片正文展示，避免折叠组件渲染问题。
     */
    @SuppressWarnings("unchecked")
    private MissingData queryMissing(Project p) {
        if (projectQueryService == null) return null;
        Map<String, Object> data;
        try {
            data = projectQueryService.missingAllowed(p.getId());
        } catch (Exception e) {
            log.warn("query missing submitters failed, skip projectId={} reason={}",
                    p.getId(), e.getMessage());
            return null;
        }
        if (!Boolean.TRUE.equals(data.get("enabled"))) return null;
        int missingCount = ((Number) data.getOrDefault("missingCount", 0)).intValue();
        if (missingCount <= 0) return null;
        List<Map<String, String>> missing = (List<Map<String, String>>) data.getOrDefault("missing", List.of());
        List<String> keys = (List<String>) data.getOrDefault("keys", List.of());
        return new MissingData(missingCount, keys, missing);
    }

    private record MissingData(int missingCount, List<String> keys, List<Map<String, String>> missing) {}

    /** 2.0 markdown 块的标准结构：{tag:div, text:{tag:lark_md, content:...}} */
    private static Map<String, Object> larkMdDiv(String md) {
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("tag", "lark_md");
        text.put("content", md);
        div.put("text", text);
        return div;
    }

    /** 转义飞书 markdown 中可能引发渲染异常的字符。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("[", "\\[").replace("]", "\\]");
    }
}
