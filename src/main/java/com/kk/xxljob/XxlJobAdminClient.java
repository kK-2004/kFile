package com.kk.xxljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.config.XxlJobConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * XXL-JOB 调度中心（admin）REST 客户端：动态创建/更新/删除一次性任务。
 *
 * admin 接口默认需要登录态（Cookie XXL_JOB_LOGIN_IDENTITY），客户端启动时登录拿 cookie 并缓存，
 * 调用失败（401 等）时自动重新登录一次。
 *
 * 仅当 xxl.job.enabled=true 时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class XxlJobAdminClient {

    private static final String LOGIN_COOKIE = "XXL_JOB_LOGIN_IDENTITY";

    private final XxlJobConfig config;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedCookie;

    public XxlJobAdminClient(XxlJobConfig config) {
        this.config = config;
    }

    /**
     * 创建任务并自动启动（admin 默认创建为 STOPPED，不启动则不会被调度触发）。
     * 返回 admin 分配的任务 id。
     */
    public long addJob(String handler, String param, String cron) {
        MultiValueMap<String, String> form = baseForm(handler, param, cron);
        String content = postWithRetry("/jobinfo/add", form);
        long jobId = Long.parseLong(content);
        startJob(jobId);
        return jobId;
    }

    /** 更新已有任务的调度配置。admin 的 update 会保留任务原状态，故仍需显式 start 确保处于 RUNNING。 */
    public void updateJob(long jobId, String handler, String param, String cron) {
        MultiValueMap<String, String> form = baseForm(handler, param, cron);
        form.add("id", String.valueOf(jobId));
        postWithRetry("/jobinfo/update", form);
        startJob(jobId);
    }

    /** 删除任务（任务被自动启动后会进入已调度，remove 仍可删除）。 */
    public void removeJob(long jobId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(jobId));
        postWithRetry("/jobinfo/remove", form);
    }

    /**
     * 启动任务（STOPPED → RUNNING）。已 RUNNING 时调用是幂等的（admin 返回成功）。
     * 任何失败（如任务已被删除）仅抛异常，由上层 catch 降级。
     */
    public void startJob(long jobId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id", String.valueOf(jobId));
        postWithRetry("/jobinfo/start", form);
    }

    private MultiValueMap<String, String> baseForm(String handler, String param, String cron) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("jobGroup", String.valueOf(config.getExecutor().getJobGroup()));
        form.add("jobDesc", "deadline-remind-" + handler);
        form.add("author", "kfile");
        form.add("alarmEmail", "");
        form.add("scheduleType", "CRON");
        form.add("scheduleConf", cron);
        form.add("cronGen_display", cron);
        form.add("glueType", "BEAN");
        form.add("executorHandler", handler);
        form.add("executorParam", param == null ? "" : param);
        form.add("executorRouteStrategy", "FIRST");
        form.add("childJobId", "");
        form.add("misfireStrategy", "FIRE_ONCE_NOW");
        form.add("executorBlockStrategy", "SERIAL_EXECUTION");
        form.add("executorTimeout", "60");
        form.add("executorFailRetryCount", "2");
        return form;
    }

    private String postWithRetry(String path, MultiValueMap<String, String> form) {
        try {
            return post(path, form, ensureCookie());
        } catch (AuthRequiredException e) {
            log.info("XXL-JOB admin cookie expired, re-login");
            cachedCookie = null;
            return post(path, form, ensureCookie());
        }
    }

    private String post(String path, MultiValueMap<String, String> form, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, cookie);
        String url = config.getAdmin().getAddresses().replaceAll("/+$", "") + path;
        ResponseEntity<String> resp = restTemplate.postForEntity(url,
                new HttpEntity<>(form, headers), String.class);
        String body = resp.getBody();
        try {
            JsonNode node = mapper.readTree(body);
            int code = node.path("code").asInt(-1);
            if (code != 200) {
                String msg = node.path("msg").asText("unknown");
                // admin 校验失败时也常常是 401/未登录风格，按需重试
                if (resp.getStatusCode().value() == 401 || msg.contains("登录") || code == 401) {
                    throw new AuthRequiredException(msg);
                }
                throw new XxlJobAdminException("xxl-job admin 调用失败: code=" + code + " msg=" + msg);
            }
            JsonNode content = node.get("content");
            return content == null || content.isNull() ? "" : content.asText("");
        } catch (AuthRequiredException ae) {
            throw ae;
        } catch (Exception e) {
            throw new XxlJobAdminException("解析 xxl-job admin 响应失败: " + e.getMessage(), e);
        }
    }

    private String ensureCookie() {
        String c = cachedCookie;
        if (c != null) return c;
        synchronized (this) {
            if (cachedCookie != null) return cachedCookie;
            String addr = config.getAdmin().getAddresses();
            if (addr == null || addr.isBlank()) {
                throw new XxlJobAdminException("xxl.job.admin.addresses 未配置");
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("userName", config.getAdmin().getUsername());
            form.add("password", config.getAdmin().getPassword());
            form.add("ifRemember", "on");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    addr.replaceAll("/+$", "") + "/login",
                    new HttpEntity<>(form, headers), String.class);
            // 登录成功时 admin 返回 {code:200}，并把 XXL_JOB_LOGIN_IDENTITY 写入 Set-Cookie
            HttpHeaders respHeaders = resp.getHeaders();
            List<String> setCookies = respHeaders.get(HttpHeaders.SET_COOKIE);
            if (setCookies == null || setCookies.isEmpty()) {
                throw new XxlJobAdminException("xxl-job admin 登录未返回 cookie，账号/密码可能错误");
            }
            String identity = null;
            for (String sc : setCookies) {
                if (sc.startsWith(LOGIN_COOKIE + "=")) {
                    int end = sc.indexOf(';');
                    identity = end > 0 ? sc.substring(0, end) : sc;
                    break;
                }
            }
            if (identity == null) {
                throw new XxlJobAdminException("xxl-job admin 登录未返回 " + LOGIN_COOKIE);
            }
            cachedCookie = identity;
            return identity;
        }
    }

    /** 用于失败时清空 cookie 的内部信号。 */
    private static class AuthRequiredException extends RuntimeException {
        AuthRequiredException(String m) { super(m); }
    }

    /** admin 调用异常的统一类型，便于上层 catch + 日志降级。 */
    public static class XxlJobAdminException extends RuntimeException {
        public XxlJobAdminException(String m) { super(m); }
        public XxlJobAdminException(String m, Throwable c) { super(m, c); }
    }
}
