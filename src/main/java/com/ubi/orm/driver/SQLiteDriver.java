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
 * SQLite数据库驱动（单例模式）
 * 功能：实现SQLite特有的SQL处理和连接管理，连接池采用懒加载
 */
public class SQLiteDriver implements DatabaseDriver {

    // 单例实例（静态内部类实现）
    private static class SingletonHolder {
        private static final SQLiteDriver INSTANCE = new SQLiteDriver();
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
    private SQLiteDriver() {
        this.configManager = ConfigManager.getInstance();
    }

    /**
     * 获取SQLiteDriver单例实例
     * @return 唯一的SQLiteDriver实例
     */
    public static SQLiteDriver getInstance() {
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
                    Map<String, DbConfig.SqliteConfig> configs = configManager.getDbConfig().getSqlite();
                    if (configs != null) {
                        for (Map.Entry<String, DbConfig.SqliteConfig> entry : configs.entrySet()) {
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
     * 创建SQLite连接池
     * @param config SQLite数据源配置
     * @return HikariDataSource连接池
     */
    private HikariDataSource createDataSource(DbConfig.SqliteConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + config.getFilePath()); // SQLite通过文件路径连接
        hc.setDriverClassName("org.sqlite.JDBC"); // 指定SQLite驱动类
        // 连接池参数
        hc.setMaximumPoolSize(config.getPool().getMaxPoolSize());
        hc.setMinimumIdle(config.getPool().getMinIdle());
        hc.setConnectionTimeout(config.getPool().getConnectionTimeout());
        hc.setIdleTimeout(config.getPool().getIdleTimeout());
        hc.setInitializationFailTimeout(0); // 懒加载：启动不检查连接
        return new HikariDataSource(hc);
    }


    /**
     * 获取SQLite连接（触发连接池懒加载）
     * @param host 数据源标识
     * @return 数据库连接
     * @throws SQLException 数据源不存在或连接失败时抛出
     */
    @Override
    public Connection getConnection(String host) throws SQLException {
        initDataSources();
        HikariDataSource ds = dataSources.get(host);
        if (ds == null) {
            throw new SQLException("SQLite数据源不存在: " + host);
        }
        return ds.getConnection();
    }

    /**
     * 执行查询操作
     * @param sql SQL语句
     * @param params 参数列表
     * @param host 数据源标识
     * @return 结果集
     * @throws SQLException 执行失败时抛出
     */
    @Override
    public ResultSet query(String sql, List<Object> params, String host) throws SQLException {
        try (Connection conn = getConnection(host);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            return ps.executeQuery();
        }
    }

    /**
     * 执行更新操作
     * @param sql SQL语句
     * @param params 参数列表
     * @param host 数据源标识
     * @return 影响行数
     * @throws SQLException 执行失败时抛出
     */
    @Override
    public Map<String,Object> execute(String sql, List<Object> params, String host) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        try (Connection conn = getConnection(host);
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParams(ps, params); // 绑定参数
            int affectedRows = ps.executeUpdate();
            result.put("affectedRows", affectedRows);
            // 获取生成的主键（如自增ID）
            ResultSet generatedKeys = ps.getGeneratedKeys();
            if (generatedKeys.next()) {
                long id = generatedKeys.getLong(1); // 获取自动生成的主键值
                result.put("id", id);
            }
            return result;
        }
    }

    /**
     * 绑定SQLite参数（索引从1开始）
     * @param ps PreparedStatement对象
     * @param params 参数列表
     * @throws SQLException 参数绑定失败时抛出
     */
    private void setParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * 关闭连接池
     */
    @Override
    public void close() {
        if (dataSources != null) {
            dataSources.values().forEach(HikariDataSource::close);
        }
    }
}