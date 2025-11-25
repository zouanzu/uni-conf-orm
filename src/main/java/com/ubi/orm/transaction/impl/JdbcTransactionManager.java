package com.ubi.orm.transaction.impl;

import com.ubi.orm.transaction.TransactionManager;
import com.ubi.orm.transaction.TransactionStatus;
import lombok.Data;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 基于JDBC的事务管理器实现
 */
public class JdbcTransactionManager implements TransactionManager {
    // 线程本地变量：存储当前线程的事务状态（确保线程安全）
    private final ThreadLocal<JdbcTransactionStatus> threadLocal = new ThreadLocal<>();
    // 数据源（提供数据库连接）
    private final Connection conn;

    public JdbcTransactionManager(Connection conn) {
        this.conn=conn;
    }

    /**
     * 开启事务：从数据源获取连接，禁用自动提交，绑定到当前线程
     */
    @Override
    public TransactionStatus begin() {
        try {

            // 2. 禁用自动提交（开启事务的核心操作）
            conn.setAutoCommit(false);
            // 3. 创建事务状态对象，绑定连接
            JdbcTransactionStatus status = new JdbcTransactionStatus();
            status.setConnection(conn);
            // 4. 存储到ThreadLocal，确保同一线程内共享此连接
            threadLocal.set(status);
            return status;
        } catch (SQLException e) {
            throw new RuntimeException("开启事务失败", e);
        }
    }

    /**
     * 提交事务：提交连接的事务，释放资源
     */
    @Override
    public void commit(TransactionStatus status) {
        JdbcTransactionStatus jdbcStatus = (JdbcTransactionStatus) status;
        if (jdbcStatus.isCompleted()) {
            throw new IllegalStateException("事务已完成，无法重复提交");
        }

        try {
            Connection conn = jdbcStatus.getConnection();
            if (conn != null) {
                conn.commit(); // 提交事务
            }
        } catch (SQLException e) {
            throw new RuntimeException("事务提交失败", e);
        } finally {
            // 标记事务完成，释放连接，清理ThreadLocal
            jdbcStatus.setCompleted(true);
            closeConnection(jdbcStatus.getConnection());
            threadLocal.remove();
        }
    }

    /**
     * 回滚事务：回滚连接的事务，释放资源
     */
    @Override
    public void rollback(TransactionStatus status) {
        JdbcTransactionStatus jdbcStatus = (JdbcTransactionStatus) status;
        if (jdbcStatus.isCompleted()) {
            throw new IllegalStateException("事务已完成，无法重复回滚");
        }

        try {
            Connection conn = jdbcStatus.getConnection();
            if (conn != null) {
                conn.rollback(); // 回滚事务
            }
        } catch (SQLException e) {
            throw new RuntimeException("事务回滚失败", e);
        } finally {
            // 标记事务完成，释放连接，清理ThreadLocal
            jdbcStatus.setCompleted(true);
            closeConnection(jdbcStatus.getConnection());
            threadLocal.remove();
        }
    }

    /**
     * 获取当前事务的连接（确保同一事务内的操作使用同一个连接）
     */
    @Override
    public Connection getConnection(TransactionStatus status) {
        JdbcTransactionStatus jdbcStatus = (JdbcTransactionStatus) status;
        Connection conn = jdbcStatus.getConnection();
        if (conn == null) {
            throw new RuntimeException("事务未绑定连接，请先调用begin()");
        }
        return conn;
    }

    /**
     * 关闭连接（释放资源）
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                // 恢复自动提交（避免影响连接池中的其他连接）
                conn.setAutoCommit(true);
                conn.close(); // 关闭连接（如果是连接池，实际是归还连接）
            } catch (SQLException e) {
                System.err.println("关闭连接失败：" + e.getMessage());
            }
        }
    }

    /**
     * JDBC事务状态实现：存储事务关联的连接和状态
     */
    @Data
    public static class JdbcTransactionStatus implements TransactionStatus {
        private final String transactionId; // 事务唯一标识（用于日志/调试）
        private Connection connection; // 事务关联的数据库连接
        private boolean completed; // 事务是否已完成

        public JdbcTransactionStatus() {
            this.transactionId = UUID.randomUUID().toString(); // 生成唯一事务ID
        }

        @Override
        public boolean isCompleted() {
            return completed;
        }

        @Override
        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

    }
}
