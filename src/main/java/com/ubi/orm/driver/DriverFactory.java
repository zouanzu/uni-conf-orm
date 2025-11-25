package com.ubi.orm.driver;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据库驱动工厂（单例模式）
 * 核心功能：管理所有数据库驱动实例，提供统一的驱动获取入口
 * 确保全局只有一个工厂实例，且各数据库驱动唯一
 */
public class DriverFactory {

    // 单例实例（静态内部类实现懒加载+线程安全）
    private static class SingletonHolder {
        private static final DriverFactory INSTANCE = new DriverFactory();
    }

    // 驱动缓存：key=数据库类型（如"mysql"），value=驱动实例
    private final Map<String, DatabaseDriver> drivers = new HashMap<>();

    /**
     * 私有构造器：初始化并注册所有数据库驱动（单例）
     * 确保驱动实例全局唯一，避免重复创建
     */
    private DriverFactory() {
        // 注册各数据库驱动的单例实例
        drivers.put("mysql", MySQLDriver.getInstance());
        drivers.put("mssql", MSSQLDriver.getInstance());
        drivers.put("sqlite", SQLiteDriver.getInstance());
    }

    /**
     * 获取工厂单例实例
     * @return 唯一的DriverFactory实例
     */
    public static DriverFactory getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 根据数据库类型获取对应的驱动实例
     * @param driveType 数据库类型（如"mysql"、"mssql"）
     * @return 对应的DatabaseDriver实例
     * @throws IllegalArgumentException 不支持的数据库类型时抛出
     */
    public DatabaseDriver getDriver(String driveType) {
        DatabaseDriver driver = drivers.get(driveType);
        if (driver == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + driveType);
        }
        return driver;
    }

    /**
     * 动态注册新的数据库驱动（扩展用）
     * @param type 数据库类型标识
     * @param driver 驱动实例
     */
    public void registerDriver(String type, DatabaseDriver driver) {
        drivers.put(type, driver);
    }
}