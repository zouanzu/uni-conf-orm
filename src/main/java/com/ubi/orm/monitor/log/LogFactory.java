package com.ubi.orm.monitor.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

public class LogFactory {
    // 原有模块...
    public static final String MODULE_CORE = "ORM_CORE";
    public static final String MODULE_DRIVER = "ORM_DRIVER";
    // 新增日志专用模块
    public static final String MODULE_SLOW_QUERY = "ORM_SLOW_QUERY"; // 慢查询日志
    public static final String MODULE_AUDIT = "ORM_AUDIT";           // 审计日志

    // 获取模块Logger（复用原有方法）
    public static Logger getLogger(String module) {
        return LogManager.getLogger(module);
    }

    // 动态调整日志级别（复用原有方法）
    public static void setLevel(String module, String level) {
        org.apache.logging.log4j.Level logLevel = org.apache.logging.log4j.Level.valueOf(level.toUpperCase());
        Configurator.setLevel(module, logLevel);
    }
}