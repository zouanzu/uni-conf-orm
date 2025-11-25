package com.ubi.orm.driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public interface DatabaseDriver {
    String buildPageSql(String sql, int pageSize, int offset);
    String getPlaceholder(int index);
    Connection getConnection(String host) throws SQLException;
    ResultSet query(String sql, List<Object> params, String host) throws SQLException;
    int execute(String sql, List<Object> params, String host) throws SQLException;
    void close(); // 关闭连接池
}