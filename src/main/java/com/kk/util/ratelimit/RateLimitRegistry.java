package com.kk.util.ratelimit;

import lombok.NoArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @ Author：YongKang
 * @ Date：2026-01-03-20:36
 */
public class RateLimitRegistry {
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public TokenBucket getOrCreate(String key, long capacity, long refillRate){
        return buckets.computeIfAbsent(key, k -> new TokenBucket(refillRate, capacity));
    }
}