package com.ubi.orm.transaction;
/**
 * 事务状态接口：跟踪事务的运行状态
 */
public interface TransactionStatus {
    /**
     * 判断事务是否已完成（提交或回滚）
     * @return true-已完成；false-未完成
     */
    boolean isCompleted();

    /**
     * 标记事务为已完成
     */
    void setCompleted(boolean completed);
}