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
 * MSSQL数据库驱动（单例模式）
 * 功能：实现MSSQL特有的SQL处理和连接管理，连接池采用懒加载
 * @author 邹安族
 */
public class MSSQLConnection implements DatabaseConnection {

    // 单例实例（静态内部类实现）
    private static class SingletonHolder {
        private static final MSSQLConnection INSTANCE = new MSSQLConnection();
    }

    // 配置管理器（单例）
    private final ConfigManager configManager;

    // 数据源缓存：key=数据源标识，value=连接池
    private volatile Map<String, HikariDataSource> dataSources;

    // 初始化锁：确保线程安全
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 私有构造器：初始化配置管理器
     */
    private MSSQLConnection() {
        this.configManager = ConfigManager.getInstance();
    }

    /**
     * 获取MSSQLDriver单例实例
     * @return 唯一的MSSQLDriver实例
     */
    public static MSSQLConnection getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 懒加载初始化数据源（首次使用时创建连接池）
     */
    private void initDataSources() {
        if (dataSources == null) {
            lock.lock();
            try {
                if (dataSources == null) {
                    dataSources = new HashMap<>();
                    Map<String, DbConfig.MssqlConfig> configs = configManager.getDbConfig().getMssql();
                    if (configs != null) {
                        for (Map.Entry<String, DbConfig.MssqlConfig> entry : configs.entrySet()) {
                            dataSources.put(entry.getKey(), createDataSource(entry.getValue()));
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 创建MSSQL连接池
     * @param config MSSQL数据源配置
     * @return HikariDataSource连接池
     */
    private HikariDataSource createDataSource(DbConfig.MssqlConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false",
                config.getHost(), config.getPort(), config.getDatabase()));
        hc.setUsername(config.getUser());
        hc.setPassword(config.getPassword());
        // 连接池参数
        hc.setMaximumPoolSize(config.getPool().getMaxPoolSize());
        hc.setMinimumIdle(config.getPool().getMinIdle());
        hc.setConnectionTimeout(config.getPool().getConnectionTimeout());
        hc.setIdleTimeout(config.getPool().getIdleTimeout());
        hc.setInitializationFailTimeout(0); // 懒加载：启动不检查连接
        return new HikariDataSource(hc);
    }


    /**
     * 获取MSSQL连接（触发连接池懒加载）
     * @param host 数据源标识
     * @return 数据库连接
     * @throws SQLException 数据源不存在或连接失败时抛出
     */
    @Override
    public Connection getConnection(String host) throws SQLException {
        initDataSources();
        HikariDataSource ds = dataSources.get(host);
        if (ds == null) {
            throw new SQLException("MSSQL数据源不存在: " + host);
        }
        return ds.getConnection();
    }

}
