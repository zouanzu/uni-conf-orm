package com.ubi.orm.driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据库驱动接口：定义跨数据库的通用操作规范
 * 包含分页SQL构建、参数占位符生成、连接获取、CRUD操作等核心能力
 */
public interface DatabaseDriver {
    /**
     * 获取数据库连接
     * @param host 数据源标识（对应配置中的数据源key）
     * @return 数据库连接
     * @throws SQLException 连接获取失败时抛出
     */
    Connection getConnection(String host) throws SQLException;

    /**
     * 执行查询操作
     * @param sql SQL语句
     * @param params 参数列表
     * @param host 数据源标识
     * @return 查询结果集
     * @throws SQLException 执行失败时抛出
     */
    ResultSet query(String sql, List<Object> params, String host) throws SQLException;

    /**
     * 执行更新操作（INSERT/UPDATE/DELETE）
     * @param sql SQL语句
     * @param params 参数列表
     * @param host 数据源标识
     * @return 影响的行数
     * @throws SQLException 执行失败时抛出
     */
    Map<String,Object> execute(String sql, List<Object> params, String host) throws SQLException;

    /**
     * 关闭连接池（应用退出时调用）
     */
    void close();
}