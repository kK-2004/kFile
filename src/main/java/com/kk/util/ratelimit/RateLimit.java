package com.kk.util.ratelimit;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    /*
    * 是否基于IP限流
     */
    boolean ip() default false;

    /*
    * 令牌回填率，每秒回填多少个令牌
     */
    long refillRate() default 1;

    /*
    * 令牌桶容量
     */
    long capacity() default 10;

    /*
    * 错误提示信息
     */
    String message() default "请求频繁，请稍后再试。";
}
