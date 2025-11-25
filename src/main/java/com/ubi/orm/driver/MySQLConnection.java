package com.ubi.orm.driver;

import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.DbConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MySQL数据库驱动（单例模式）
 * 功能：实现MySQL特有的SQL处理和连接管理，连接池采用懒加载（首次使用时初始化）
 */
public class MySQLConnection implements DatabaseConnection {

    // 单例实例（静态内部类实现）
    private static class SingletonHolder {
        private static final MySQLConnection INSTANCE = new MySQLConnection();
    }

    // 配置管理器（假设为单例，通过getInstance获取）
    private final ConfigManager configManager;

    // 数据源缓存：key=数据源标识（如配置中的host别名），value=连接池
    private volatile Map<String, HikariDataSource> dataSources;

    // 初始化锁：确保数据源初始化线程安全
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 私有构造器：初始化配置管理器
     * 禁止外部实例化，确保单例唯一性
     */
    private MySQLConnection() {
        this.configManager = ConfigManager.getInstance(); // 依赖ConfigManager单例
    }

    /**
     * 获取MySQLDriver单例实例
     *
     * @return 唯一的MySQLDriver实例
     */
    public static MySQLConnection getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 懒加载初始化数据源（首次使用时才创建连接池）
     * 双重检查锁定（double-checked locking）确保线程安全且高效
     */
    private void initDataSources() {
        // 第一层检查：避免锁竞争（非线程安全，但仅用于快速判断）
        if (dataSources == null) {
            lock.lock(); // 加锁：确保只有一个线程进入初始化逻辑
            try {
                // 第二层检查：防止多线程并发时重复初始化
                if (dataSources == null) {
                    dataSources = new HashMap<>();
                    // 从配置中获取所有MySQL数据源配置
                    Map<String, DbConfig.MysqlConfig> configs = configManager.getDbConfig().getMysql();
                    if (configs != null) {
                        for (Map.Entry<String, DbConfig.MysqlConfig> entry : configs.entrySet()) {
                            // 为每个数据源创建连接池（懒加载的核心：此时仅初始化，不实际创建连接）
                            dataSources.put(entry.getKey(), createDataSource(entry.getValue()));
                        }
                    }
                }
            } finally {
                lock.unlock(); // 确保锁释放
            }
        }
    }

    /**
     * 创建HikariCP连接池
     *
     * @param config MySQL数据源配置
     * @return 初始化后的HikariDataSource（连接池）
     */
    private HikariDataSource createDataSource(DbConfig.MysqlConfig config) {
        HikariConfig hc = new HikariConfig();
        // 构建JDBC连接URL
        hc.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                config.getHost(), config.getPort(), config.getDatabase()));
        hc.setUsername(config.getUser());
        hc.setPassword(config.getPassword());
        // 连接池参数配置
        hc.setMaximumPoolSize(config.getPool().getMaxPoolSize());
        hc.setMinimumIdle(config.getPool().getMinIdle());
        hc.setConnectionTimeout(config.getPool().getConnectionTimeout());
        hc.setIdleTimeout(config.getPool().getIdleTimeout());
        hc.setInitializationFailTimeout(0); // 懒加载关键：启动时不检查连接，首次获取时才建立
        return new HikariDataSource(hc);
    }


    /**
     * 获取数据库连接（触发连接池懒加载）
     *
     * @param host 数据源标识（对应配置中的key）
     * @return 数据库连接
     * @throws SQLException 数据源不存在或连接获取失败时抛出
     */
    @Override
    public Connection getConnection(String host) throws SQLException {
        initDataSources(); // 首次调用时初始化连接池
        HikariDataSource ds = dataSources.get(host);
        if (ds == null) {
            throw new SQLException("MySQL数据源不存在: " + host);
        }
        return ds.getConnection(); // 实际获取连接（此时连接池才会创建物理连接）
    }
}
