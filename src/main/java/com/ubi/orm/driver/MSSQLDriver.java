package com.ubi.orm.driver;

import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.DbConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class MSSQLDriver implements DatabaseDriver {
    private final ConfigManager configManager;
    private volatile Map<String, HikariDataSource> dataSources;
    private final ReentrantLock lock = new ReentrantLock();

    public MSSQLDriver(ConfigManager configManager) {
        this.configManager = configManager;
    }

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

    private HikariDataSource createDataSource(DbConfig.MssqlConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false",
                config.getHost(), config.getPort(), config.getDatabase()));
        hc.setUsername(config.getUser());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(config.getPool().getMaxPoolSize());
        hc.setMinimumIdle(config.getPool().getMinIdle());
        hc.setConnectionTimeout(config.getPool().getConnectionTimeout());
        hc.setIdleTimeout(config.getPool().getIdleTimeout());
        hc.setInitializationFailTimeout(0);
        return new HikariDataSource(hc);
    }

    @Override
    public String buildPageSql(String sql, int pageSize, int offset) {
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
    }

    @Override
    public String getPlaceholder(int index) {
        return "@p" + index;
    }

    @Override
    public Connection getConnection(String host) throws SQLException {
        initDataSources();
        HikariDataSource ds = dataSources.get(host);
        if (ds == null) throw new SQLException("MSSQL host not found: " + host);
        return ds.getConnection();
    }

    @Override
    public ResultSet query(String sql, List<Object> params, String host) throws SQLException {
        try (Connection conn = getConnection(host);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            return ps.executeQuery();
        }
    }

    @Override
    public int execute(String sql, List<Object> params, String host) throws SQLException {
        try (Connection conn = getConnection(host);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params);
            return ps.executeUpdate();
        }
    }

    private void setParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject("p" + i, params.get(i)); // MSSQL参数名格式：@p0
        }
    }

    @Override
    public void close() {
        if (dataSources != null) {
            dataSources.values().forEach(HikariDataSource::close);
        }
    }
}