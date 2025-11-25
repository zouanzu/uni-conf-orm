package com.ubi.orm.core;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用API返回结果类：统一所有接口的响应格式
 * @param <T> 数据类型（支持泛型）
 */
@Data
public class Result<T> implements Serializable {
    // 状态码：200=成功，其他=失败（可扩展更多业务码）
    private int code;
    // 是否成功（简化前端判断）
    private boolean success;
    // 消息（成功/失败描述）
    private String msg;
    // 业务数据（成功时返回）
    private T data;
    // 分页总数（仅分页查询时使用）
    private Long total;
    //更新插入操作影响的行数
    private int affectedRows;
    //自动生成的键
    private Integer generatedKey;
    // 私有构造（通过静态方法创建实例）
    private Result(int code, boolean success, String msg, T data, Long total,int affectedRows,Integer generatedKey) {
        this.code = code;
        this.success = success;
        this.msg = msg;
        this.data = data;
        this.total = total;
        this.affectedRows=affectedRows;
        this.generatedKey=generatedKey;
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> Result<T> success() {
        return new Result<>(200, true, "操作成功", null, null,0,null);
    }

    /**
     * 成功响应（带数据）
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, true, "操作成功", data, null,0,null);
    }

    /**
     * 成功响应（带分页数据）
     */
    public static <T> Result<T> success(T data, Long total) {
        return new Result<>(200, true, "操作成功", data, total,0,null);
    }

    /**
     * 成功响应（修改、插入操作）
     */
    public static <T> Result<T> success(int affectedRows, Integer generatedKey) {
        return new Result<>(200, true, "操作成功", null, null,affectedRows,generatedKey);
    }

    /**
     * 失败响应（带错误消息）
     */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(500, false, msg, null, null,0,null);
    }

    /**
     * 失败响应（带自定义状态码和消息）
     */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, false, msg, null, null,0,null);
    }
}
