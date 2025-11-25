package com.ubi.orm.monitor;

import com.ubi.orm.config.AuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

// 慢查询日志
public class SlowQueryLogger {
    private static final Logger logger = LoggerFactory.getLogger("SLOW_QUERY");
    private final AuthConfig authConfig;

    public SlowQueryLogger(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    public void logIfSlow(String sql, long costMs, Object[] params) {
        if (authConfig.isSlowLog() && costMs > authConfig.getSlowLogThreshold()) {
            logger.warn("Slow query: {}ms, SQL: {}, Params: {}", costMs, sql, params);
        }
    }
}

// 审计日志
public class AuditLogger {
    private static final Logger logger = LoggerFactory.getLogger("AUDIT");
    private final AuthConfig authConfig;

    public AuditLogger(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    public void log(String apiKey, Map<String, Object> params, long costMs) {
        Map<String, Object> audit = new HashMap<>();
        String prefix = authConfig.getAuditFieldPerfix();
        params.forEach((k, v) -> {
            if (k.startsWith(prefix)) audit.put(k, v);
        });
        audit.put("apiKey", apiKey);
        audit.put("costMs", costMs);
        audit.put("timestamp", System.currentTimeMillis());

        switch (authConfig.getLog().toLowerCase()) {
            case "error": logger.error("Audit: {}", audit); break;
            case "warn": logger.warn("Audit: {}", audit); break;
            default: logger.info("Audit: {}", audit);
        }
    }
}
