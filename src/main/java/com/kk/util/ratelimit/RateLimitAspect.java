package com.kk.util.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * @ Author：YongKang
 * @ Date：2026-01-03-20:41
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {
    private final RateLimitRegistry registry = new RateLimitRegistry();

    @Around("@annotation(com.kk.util.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RateLimit rl = method.getAnnotation(RateLimit.class);

        String baseKey = buildBaseKey(method);
        String key = rl.ip() ? baseKey + "#" + resolveClientIpSafe() : baseKey;

        TokenBucket bucket = registry.getOrCreate(key, rl.capacity(), rl.refillRate());

        if (!bucket.tryAcquire()) {
            String msg = rl.message();
            log.warn("Rate limit exceeded: key={}, msg={}", key, msg);
            throw new RateLimitedException(msg);
        }

        return pjp.proceed();
    }

    private String buildBaseKey(Method method) {
        // 注意：用声明类更稳（代理类会变）
        Class<?> declaring = method.getDeclaringClass();
        return declaring.getName() + "#" + method.getName();
    }

    private String resolveClientIpSafe() {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (!(ra instanceof ServletRequestAttributes sra)) return "unknown";
            HttpServletRequest req = sra.getRequest();
            return resolveClientIp(req);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveClientIp(HttpServletRequest req) {
        // 适配常见代理头（按需扩展）
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // XFF 可能是 "client, proxy1, proxy2"
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) return first;
        }
        String xrip = req.getHeader("X-Real-IP");
        if (xrip != null && !xrip.isBlank()) return xrip.trim();

        return req.getRemoteAddr();
    }
}