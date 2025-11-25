package com.ubi.orm.driver;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecuteSql {

    /**
     * 执行查询操作
     * @param sql SQL语句
     * @param params 参数列表
     * @param conn 数据源标识
     * @return 查询结果集
     * @throws SQLException 执行失败时抛出
     */

    public  ResultSet query(String sql, List<Object> params, Connection conn) throws SQLException {
        // try-with-resources自动关闭连接和PreparedStatement
        try (
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setParams(ps, params); // 绑定参数
            return ps.executeQuery();
        }
    }

    /**
     * 执行更新操作（INSERT/UPDATE/DELETE）
     * @param sql SQL语句
     * @param params 参数列表
     * @param conn 数据源标识
     * @return 影响的行数
     * @throws SQLException 执行失败时抛出
     */

    public Map<String,Object> execute(String sql, List<Object> params, Connection conn) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        try (
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
     * 绑定SQL参数（MySQL参数索引从1开始）
     * @param ps PreparedStatement对象
     * @param params 参数列表
     * @throws SQLException 参数绑定失败时抛出
     */
    private void setParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            // MySQL的setObject索引从1开始
            ps.setObject(i + 1, params.get(i));
        }
    }

}
