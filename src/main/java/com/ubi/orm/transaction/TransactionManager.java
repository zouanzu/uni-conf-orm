package com.ubi.orm.transaction;
import java.sql.Connection;

/**
 * 事务管理器接口：定义事务的核心操作规范
 */
public interface TransactionManager {
    /**
     * 开启事务
     * @return 事务状态对象
     */
    TransactionStatus begin();

    /**
     * 提交事务
     * @param status 事务状态（由begin()返回）
     */
    void commit(TransactionStatus status);

    /**
     * 回滚事务
     * @param status 事务状态（由begin()返回）
     */
    void rollback(TransactionStatus status);

    /**
     * 获取当前事务的数据库连接
     * @param status 事务状态
     * @return 事务内的连接（已禁用自动提交）
     */
    Connection getConnection(TransactionStatus status);
}
