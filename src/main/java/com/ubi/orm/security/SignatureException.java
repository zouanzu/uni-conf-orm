package com.ubi.orm.security;

/**
 * 签名验证异常：当签名验证失败时抛出（如签名不匹配、过期等）
 *
 * @author 邹安族
 * @date 2025年11月19日
 */
public class SignatureException extends Exception {

    /**
     * 无参构造器
     */
    public SignatureException() {
        super();
    }

    /**
     * 带错误消息的构造器
     *
     * @param message 错误详情（如"签名不匹配"、"签名已过期"等）
     */
    public SignatureException(String message) {
        super(message);
    }

    /**
     * 带错误消息和根因的构造器
     *
     * @param message 错误详情
     * @param cause   原始异常（如计算签名时的异常）
     */
    public SignatureException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 带根因的构造器
     *
     * @param cause 原始异常
     */
    public SignatureException(Throwable cause) {
        super(cause);
    }
}
