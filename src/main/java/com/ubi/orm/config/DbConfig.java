package com.ubi.orm.config;

import lombok.Data;
import java.util.Map;

@Data
public class DbConfig {
    private Map<String, MysqlConfig> mysql;
    private Map<String, MssqlConfig> mssql;
    private Map<String, SqliteConfig> sqlite;

    @Data
    public static class MysqlConfig {
        private String host = "localhost";
        private int port = 3306;
        private String database;
        private String user;
        private String password;
        private PoolConfig pool = new PoolConfig();
    }

    @Data
    public static class MssqlConfig {
        private String host = "localhost";
        private int port = 1433;
        private String database;
        private String user;
        private String password;
        private PoolConfig pool = new PoolConfig();
    }

    @Data
    public static class SqliteConfig {
        private String filePath; // 如: ./data/sqlite.db
        private PoolConfig pool = new PoolConfig();
    }

    @Data
    public static class PoolConfig {
        private int maxPoolSize = 10;
        private int minIdle = 0; // 懒加载：初始不创建连接
        private long connectionTimeout = 30000;
        private long idleTimeout = 600000;
    }
}
