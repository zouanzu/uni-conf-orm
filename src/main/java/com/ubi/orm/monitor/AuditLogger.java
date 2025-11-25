package com.ubi.orm.monitor;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.monitor.log.LogFactory;
import org.apache.logging.log4j.Logger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审计日志记录器：记录操作详情，用于安全审计和行为追溯
 */
public class AuditLogger {
    private static final Logger AUDIT_LOGGER = LogFactory.getLogger(LogFactory.MODULE_AUDIT);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


    /**
     * 记录审计日志
     * @param apiKey 操作人标识（如API密钥、用户名）
     * @param auditString 解析后的请求参数
     * @param costTime 操作耗时（毫秒）
     */
    public void log(String apiKey, String auditString, long costTime) {
        // 审计日志必须包含：时间、操作人、耗时
        StringBuilder logMsg = new StringBuilder()
                .append("操作审计 | 时间: ")
                .append(LocalDateTime.now().format(TIME_FORMATTER))
                .append(" | apiKey: ")
                .append(apiKey)
                .append(" | 耗时: ")
                .append(costTime)
                .append("ms")
                .append("内容：")
                .append(auditString);



        // 审计日志通常用INFO级别，确保不丢失
        AUDIT_LOGGER.info(logMsg.toString());
    }

    /**
     * 敏感参数脱敏（更严格的审计脱敏）
     */
    private String maskSensitiveParams(String params) {
        if (params == null) {
            return "null";
        }
        // 脱敏规则：替换手机号、邮箱、密码等
        String masked = params
                .replaceAll("(1[3-9]\\d{9})", "***手机号***")
                .replaceAll("([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})", "***邮箱***")
                .replaceAll("(password|token|secret)[:=]\\s*[^,;}]+", "$1=***脱敏***");
        return masked;
    }
}