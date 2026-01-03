package com.kk.util.ratelimit;

/**
 * @ Author：YongKang
 * @ Date：2026-01-03-20:20
 */
public class TokenBucket {
    private final long capacity;
    private long tokens;
    private long lastRefillAt;
    private final long refillRate;

    public TokenBucket(long refillRate, long capacity) {
        this.refillRate = refillRate;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillAt = System.nanoTime();
    }

    public synchronized boolean tryAcquire(){
        refill();
        if(tokens > 0){
            tokens--;
            return true;
        }
        return false;
    }

    private void refill(){
        long now = System.nanoTime();
        long add = (now - lastRefillAt) * refillRate / 1_000_000_000;
        if(add > 0){
            tokens = Math.min(capacity, tokens + add);
            lastRefillAt = now;
        }
    }
}