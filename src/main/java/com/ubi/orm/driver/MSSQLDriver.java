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
 */
public class MSSQLDriver implements DatabaseDriver {

    // 单例实例（静态内部类实现）
    private static class SingletonHolder {
        private static final MSSQLDriver INSTANCE = new MSSQLDriver();
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
    private MSSQLDriver() {
        this.configManager = ConfigManager.getInstance();
    }

    /**
     * 获取MSSQLDriver单例实例
     * @return 唯一的MSSQLDriver实例
     */
    public static MSSQLDriver getInstance() {
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
     * 绑定MSSQL参数（参数索引从0开始，对应@p0）
     * @param ps PreparedStatement对象
     * @param params 参数列表
     * @throws SQLException 参数绑定失败时抛出
     */
    private void setParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            // MSSQL的setObject索引从0开始（与占位符@p0对应）
            ps.setObject(i, params.get(i));
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