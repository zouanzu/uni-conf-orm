package com.ubi.orm.monitor.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * 日志工厂：统一管理各模块Logger，支持动态调整日志级别
 */
public class LogFactory {
    // 模块日志名称（与log4j2配置对应）
    public static final String MODULE_CORE = "ORM_CORE";       // 核心处理器
    public static final String MODULE_DRIVER = "ORM_DRIVER";   // 数据库驱动
    public static final String MODULE_SECURITY = "ORM_SECURITY"; // 安全组件
    public static final String MODULE_JOB = "ORM_JOB";         // 任务流引擎
    public static final String MODULE_MONITOR = "ORM_MONITOR"; // 监控组件

    /**
     * 获取指定模块的Logger
     */
    public static Logger getLogger(String module) {
        return LogManager.getLogger(module);
    }

    /**
     * 动态调整日志级别（支持运行时修改）
     * @param module 模块名称
     * @param level 日志级别（trace/debug/info/warn/error/fatal）
     */
    public static void setLevel(String module, String level) {
        org.apache.logging.log4j.Level logLevel = org.apache.logging.log4j.Level.valueOf(level.toUpperCase());
        Configurator.setLevel(module, logLevel);
    }
}
