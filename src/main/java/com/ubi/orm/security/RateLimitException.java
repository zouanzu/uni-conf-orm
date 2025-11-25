package com.ubi.orm.security;

/**
 * 限流异常：当请求触发限流规则时抛出（如单位时间内请求次数超限）
 *
 * @author 邹安族
 * @date 2025年11月19日
 */
public class RateLimitException extends Exception {

    /**
     * 无参构造器
     */
    public RateLimitException() {
        super();
    }

    /**
     * 带错误消息的构造器
     *
     * @param message 错误详情（如"单位时间内请求次数超限，当前限制：100次/分钟"）
     */
    public RateLimitException(String message) {
        super(message);
    }

    /**
     * 带错误消息和根因的构造器
     *
     * @param message 错误详情
     * @param cause   原始异常（如限流计数时的异常）
     */
    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 带根因的构造器
     *
     * @param cause 原始异常
     */
    public RateLimitException(Throwable cause) {
        super(cause);
    }
}
