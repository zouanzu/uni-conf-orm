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
        private String server= "localhost";
        private String host = "localhost";
        private int port = 1433;
        private String database;
        private String user;
        private String password;
        private Options options = new Options();
        private PoolConfig pool = new PoolConfig();
    }

    @Data
    public static class Options {
        private boolean  encrypt= true;
    }
    @Data
    public static class SqliteConfig {
        private String filePath; // 如: ./data/sqlite.db
        private  String filename;
        private PoolConfig pool = new PoolConfig();
        private long timeout= 5000;
    }

    @Data
    public static class PoolConfig {
        private int maxPoolSize = 10;
        private int minIdle = 0; // 懒加载：初始不创建连接
        private long connectionTimeout = 30000;
        private long idleTimeout = 600000;
        private int connectionLimit= 5;
        private boolean waitForConnections= true;
        private long queueLimit=100;
        private  int max= 15;
        private  int min= 0;
        private long idleTimeoutMillis=60000;
        private  long acquireTimeoutMillis= 15000;
    }
}
