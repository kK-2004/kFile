package com.kk.project.task;

import com.kk.project.service.ProjectDeadlineReminderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 项目截止时间提醒 XxlJob handler。
 *
 * 触发时机：调度中心在 endAt-12h 那一刻调用本 handler。
 * 参数格式：{@code deadline-12h-{projectId}}（由 {@link ProjectDeadlineReminderService#paramOf} 生成）。
 *
 * 仅当 xxl.job.enabled=true 时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class ProjectDeadlineRemindJob {

    @Autowired(required = false)
    private ProjectDeadlineReminderService reminderService;

    @XxlJob("projectDeadlineRemindJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        Long projectId = ProjectDeadlineReminderService.parseProjectId(param);
        if (projectId == null) {
            log.warn("projectDeadlineRemindJob invalid param: {}", param);
            XxlJobHelper.handleFail("invalid param: " + param);
            return;
        }
        if (reminderService == null) {
            // xxl-job 启用但 kmessage 未启用：直接标记成功跳过，不触发失败重试
            log.info("kmessage 未启用，跳过截止提醒发送 projectId={}", projectId);
            XxlJobHelper.handleSuccess("kmessage disabled, skipped");
            return;
        }
        try {
            reminderService.sendReminder(projectId);
            XxlJobHelper.handleSuccess("sent");
        } catch (Exception e) {
            log.warn("projectDeadlineRemindJob failed projectId={} reason={}", projectId, e.getMessage());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }
}
