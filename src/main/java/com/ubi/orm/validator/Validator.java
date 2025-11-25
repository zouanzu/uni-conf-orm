package com.ubi.orm.validator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

// 单个校验器（如非空、长度等）
@Data
// 单个字段的校验规则（对应JSON中的一个对象）
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略JSON中未定义的字段
public class Validator {
    // getter/setter
    private String type; // 校验类型（notNull、minLength等）
    private Object param; // 校验参数（如minLength的2、pattern的正则表达式）
    private String message; // 错误提示信息

}
