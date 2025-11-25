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

    static {
        LogFactory.setLevel(LogFactory.MODULE_CORE,"debug");
    }
    // ----------------- 核心模块日志 -----------------
    public static void coreDebug(String msg, Object... args) {
         coreLog.debug(msg, args);
    }

    public static void coreInfo(String msg, Object... args) {
        coreLog.info(msg, args);
    }

    public static void coreWarn(String msg, Object... args) {
         coreLog.warn(msg, args);
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


}