package com.ubi.orm.monitor;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.monitor.log.LogFactory;
import org.apache.logging.log4j.Logger;
import java.util.Arrays;

/**
 * 慢查询日志记录器：记录超过阈值的SQL查询
 */
public class SlowQueryLogger {
    private static final Logger SLOW_QUERY_LOGGER = LogFactory.getLogger(LogFactory.MODULE_SLOW_QUERY);
    private final long slowThresholdMs; // 慢查询阈值（毫秒）

    // 构造器：从AuthConfig获取阈值，默认1000ms
    public SlowQueryLogger(AuthConfig authConfig) {
        // 优先从配置获取，无配置则用默认值
        this.slowThresholdMs = (authConfig != null && (Integer) authConfig.getSlowLogThreshold() != null)
                ? authConfig.getSlowLogThreshold()
                : 1000; // 默认1秒
    }

    /**
     * 当查询耗时超过阈值时记录慢查询日志
     * @param sql SQL语句
     * @param costTime 耗时（毫秒）
     * @param args SQL参数（避免敏感信息，如密码）
     */
    public void logIfSlow(String sql, long costTime, Object[] args) {
        // 耗时未超过阈值，不记录
        if (costTime < slowThresholdMs) {
            return;
        }

        // 参数脱敏（避免日志泄露密码等敏感信息）
        Object[] maskedArgs = maskSensitiveArgs(args);

        // 日志内容：包含耗时、SQL、参数（便于排查性能问题）
        SLOW_QUERY_LOGGER.warn(
                "慢查询 detected | 耗时: {}ms (阈值: {}ms) | SQL: {} | 参数: {}",
                costTime,
                slowThresholdMs,
                sql,
                Arrays.toString(maskedArgs)
        );
    }

    /**
     * 敏感参数脱敏（如密码、token等）
     */
    private Object[] maskSensitiveArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return new Object[0];
        }
        Object[] masked = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                masked[i] = null;
                continue;
            }
            // 简单规则：假设字符串包含"password"、"token"等关键词则脱敏
            String argStr = arg.toString();
            if (argStr.contains("password") || argStr.contains("token") || argStr.contains("secret")) {
                masked[i] = "***脱敏***";
            } else {
                masked[i] = arg;
            }
        }
        return masked;
    }
}


