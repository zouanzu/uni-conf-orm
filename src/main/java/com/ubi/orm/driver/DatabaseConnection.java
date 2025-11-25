package com.ubi.orm.driver;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseConnection {
    /**
     * 获取数据库连接
     * @param host 数据源标识（对应配置中的数据源key）
     * @return 数据库连接
     * @throws SQLException 连接获取失败时抛出
     */
    Connection getConnection(String host) throws SQLException;
}
