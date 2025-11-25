package com.ubi.orm.security;

import com.ubi.orm.config.AuthConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// 限流控制器
public class RateLimiter {
    private final Map<String, RequestRecord> records = new ConcurrentHashMap<>();
    private final AuthConfig authConfig;

    public RateLimiter(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    public void check(String clientId) {
        long now = System.currentTimeMillis();
        RequestRecord record = records.computeIfAbsent(clientId, k -> new RequestRecord());

        // 清理过期记录
        long window = TimeUnit.SECONDS.toMillis(authConfig.getRateLimitWindow());
        record.timestamps.removeIf(t -> t < now - window);

        // 限流检查
        if (authConfig.getRateLimitMax() > 0 && record.timestamps.size() >= authConfig.getRateLimitMax()) {
            throw new SecurityException("Rate limit exceeded");
        }

        // 防抖检查
        if (authConfig.getIntervalMin() > 0 && !record.timestamps.isEmpty()) {
            long last = record.timestamps.getLast();
            if (now - last < authConfig.getIntervalMin()) {
                throw new SecurityException("Request interval too small");
            }
        }

        record.timestamps.add(now);
    }

    private static class RequestRecord {
        final java.util.Deque<Long> timestamps = new java.util.ArrayDeque<>();
    }
}
