package com.kk.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置。
 *
 * 仅当 xxl.job.enabled=true 时生效。开启后会把当前应用注册为执行器，
 * {@code @XxlJob} 标注的 handler 即可被调度中心触发。
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "xxl.job")
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
@Data
public class XxlJobConfig {

    /** 调度中心（admin）相关 */
    private Admin admin = new Admin();
    /** 通讯令牌，需与调度中心 xxl.job.accessToken 一致 */
    private String accessToken;
    /** 执行器相关 */
    private Executor executor = new Executor();

    @Data
    public static class Admin {
        /** 调度中心根地址，如 http://127.0.0.1:8080/xxl-job-admin */
        private String addresses;
        /** admin 登录账号（用于 REST API 动态建任务） */
        private String username;
        /** admin 登录密码 */
        private String password;
    }

    @Data
    public static class Executor {
        /** 执行器 appname，需与 admin 中执行器分组一致 */
        private String appname = "kfile-executor";
        /** 执行器注册地址（留空则自动获取） */
        private String address;
        /** 执行器 IP（留空则自动获取） */
        private String ip;
        /** 执行器端口（留空则自动获取） */
        private Integer port;
        /** 日志路径 */
        private String logpath = "/data/applogs/xxl-job/jobhandler";
        /** 日志保留天数 */
        private int logretentiondays = 30;
        /** admin 中执行器分组 id（动态创建任务时使用） */
        private int jobGroup;
    }

    @Bean(initMethod = "start", destroyMethod = "destroy")
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("XXL-JOB executor initializing: admin={}, appname={}, port={}",
                admin.getAddresses(), executor.getAppname(),
                executor.getPort() == null ? "auto" : executor.getPort());
        XxlJobSpringExecutor e = new XxlJobSpringExecutor();
        e.setAdminAddresses(admin.getAddresses());
        e.setAccessToken(accessToken);
        e.setAppname(executor.getAppname());
        e.setAddress(executor.getAddress());
        e.setIp(executor.getIp());
        // port: 显式配置优先；未配则设 -1，由 xxl-job 自动选择可用端口。
        // 避免本机多实例 / 调试时端口（默认 9999）冲突导致 EmbedServer bind 失败。
        // 配 0 也会被 xxl-job 视为「未指定」走默认 9999，所以统一映射成 -1。
        int port = (executor.getPort() == null || executor.getPort() <= 0)
                ? -1 : executor.getPort();
        e.setPort(port);
        e.setLogPath(executor.getLogpath());
        e.setLogRetentionDays(executor.getLogretentiondays());
        return e;
    }
}
