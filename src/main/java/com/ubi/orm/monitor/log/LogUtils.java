package com.ubi.orm.monitor.log;

import org.apache.logging.log4j.Logger;

/**
 * 日志工具类：封装常用日志操作，减少模板代码
 */
public class LogUtils {
    // 核心模块日志
    private static final Logger coreLog = LogFactory.getLogger(LogFactory.MODULE_CORE);
    // 数据库驱动日志
    private static final Logger driverLog = LogFactory.getLogger(LogFactory.MODULE_DRIVER);
    // 安全组件日志
    private static final Logger securityLog = LogFactory.getLogger(LogFactory.MODULE_SECURITY);
    // 任务流日志
    private static final Logger jobLog = LogFactory.getLogger(LogFactory.MODULE_JOB);
    // 监控日志
    private static final Logger monitorLog = LogFactory.getLogger(LogFactory.MODULE_MONITOR);

    // ----------------- 核心模块日志 -----------------
    public static void coreDebug(String msg, Object... args) {
        if (coreLog.isDebugEnabled()) coreLog.debug(msg, args);
    }

    public static void coreInfo(String msg, Object... args) {
        if (coreLog.isInfoEnabled()) coreLog.info(msg, args);
    }

    public static void coreWarn(String msg, Object... args) {
        if (coreLog.isWarnEnabled()) coreLog.warn(msg, args);
    }

    public static void coreError(String msg, Throwable t) {
        coreLog.error(msg, t);
    }

    // ----------------- 数据库驱动日志 -----------------
    public static void driverDebug(String msg, Object... args) {
        if (driverLog.isDebugEnabled()) driverLog.debug(msg, args);
    }

    public static void driverInfo(String msg, Object... args) {
        if (driverLog.isInfoEnabled()) driverLog.info(msg, args);
    }

    public static void driverError(String msg, Throwable t) {
        driverLog.error(msg, t);
    }

    // ----------------- 安全组件日志 -----------------
    public static void securityInfo(String msg, Object... args) {
        if (securityLog.isInfoEnabled()) securityLog.info(msg, args);
    }

    public static void securityWarn(String msg, Object... args) {
        if (securityLog.isWarnEnabled()) securityLog.warn(msg, args);
    }

    public static void securityError(String msg, Throwable t) {
        securityLog.error(msg, t);
    }

    // ----------------- 任务流日志 -----------------
    public static void jobDebug(String msg, Object... args) {
        if (jobLog.isDebugEnabled()) jobLog.debug(msg, args);
    }

    public static void jobInfo(String msg, Object... args) {
        if (jobLog.isInfoEnabled()) jobLog.info(msg, args);
    }

    public static void jobError(String msg, Throwable t) {
        jobLog.error(msg, t);
    }

    // ----------------- 监控日志 -----------------
    public static void monitorInfo(String msg, Object... args) {
        if (monitorLog.isInfoEnabled()) monitorLog.info(msg, args);
    }

    public static void monitorWarn(String msg, Object... args) {
        if (monitorLog.isWarnEnabled()) monitorLog.warn(msg, args);
    }
}