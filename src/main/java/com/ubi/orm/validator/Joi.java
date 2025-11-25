package com.ubi.orm.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubi.orm.param.StandardParams;

import java.util.*;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;
import java.text.ParseException;

/**
 * 参数校验核心类：加载规则、执行校验
 */
public class Joi {
    private final ParamsMapping paramMapping;  // 所有字段的校验规则

    // 构造方法：从paramsMapping的JSON字符串加载规则
    public Joi(String paramMappingJson) throws Exception {
        if (paramMappingJson == null || paramMappingJson.trim().isEmpty()) {
            throw new IllegalArgumentException("参数规则不能为空");
        }
        ObjectMapper objectMapper = new ObjectMapper();
        // 将JSON数组解析为FieldRule列表（需引入Jackson依赖）
        this.paramMapping = objectMapper.readValue(paramMappingJson, new TypeReference<ParamsMapping>() {});
    }

    // 构造方法：直接从ParamsMapping列表加载规则（用于非JSON场景）
    public Joi(ParamsMapping paramMapping) {
        if (paramMapping == null ) {
            throw new IllegalArgumentException("参数规则不能为空");
        }
        this.paramMapping = paramMapping;
    }

    /**
     * 核心校验方法
     * @param value 数据源：key为source（如"body"、"params"），value为对应的数据Map（如body的所有参数）
     * @return 校验错误：key为字段名（或alias），value为错误消息列表
     */
    public Object validate(Object value) throws Exception {
            Map<String, List<String>> errors = new HashMap<>();
            String field = paramMapping.getField();
            String source = paramMapping.getSource();
            String alias = paramMapping.getAlias() != null ? paramMapping.getAlias() : field; // 优先使用别名


            //  执行该字段的所有验证规则
            for (Validator validator : paramMapping.getValidators()) {
                if (!check(validator, value, alias)) {
                    // 错误消息：优先使用自定义message，否则用默认消息
                    String errorMsg = validator.getMessage() != null ? validator.getMessage() :
                            getDefaultMessage(field, validator.getType(), validator.getParam());
                    throw new Exception(errorMsg);
                }
            }



        return value;
    }


    /**
     * 执行单个验证规则
     * @param validator 验证器
     * @param value 字段值
     * @param field 字段名（用于生成错误消息）
     * @return 验证是否通过
     */
    private boolean check(Validator validator, Object value, String field) {
        String type = normalizeValidatorType(validator.getType()); // 统一验证类型格式（如maxlen→maxLength）
        Object param = validator.getParam();

        // 处理null值（特殊逻辑：required验证需单独处理，其他验证null视为不通过）
        if (value == null) {
            return !"required".equals(type) ;
        }

        // 转换为字符串用于格式校验（保留原始值用于类型判断）
        String strValue = value.toString();

        switch (type) {
            case "required":
                // 非空校验：字符串不能为空白，其他类型不能为null（已在上方处理null）
                return !(value instanceof String) || !((String) value).trim().isEmpty();

            case "number":
                // 数字校验（支持整数、小数、科学计数法）
                try {
                    Double.parseDouble(strValue);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }

            case "integer":
                // 整数校验（严格匹配整数）
                try {
                    Long.parseLong(strValue);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }

            case "string":
                // 字符串类型校验
                return value instanceof String;

            case "min":
                // 数值最小值校验（支持数字类型或数字字符串）
                try {
                    double val = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(strValue);
                    double min = ((Number) param).doubleValue();
                    return val >= min;
                } catch (Exception e) {
                    return false;
                }

            case "max":
                // 数值最大值校验
                try {
                    double val = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(strValue);
                    double max = ((Number) param).doubleValue();
                    return val <= max;
                } catch (Exception e) {
                    return false;
                }

            case "minLength":
                // 字符串最小长度校验
                if (!(value instanceof String)) {
                    return false; // 非字符串类型不通过
                }
                int minLen = ((Number) param).intValue();
                return ((String) value).length() >= minLen;

            case "maxLength":
                // 字符串最大长度校验
                if (!(value instanceof String)) {
                    return false;
                }
                int maxLen = ((Number) param).intValue();
                return ((String) value).length() <= maxLen;

            case "length":
                // 字符串固定长度校验
                if (!(value instanceof String)) {
                    return false;
                }
                int len = ((Number) param).intValue();
                return ((String) value).length() == len;

            case "email":
                // 邮箱格式校验
                String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$";
                return Pattern.matches(emailRegex, strValue);

            case "phone":
                // 中国大陆手机号校验
                return Pattern.matches("^1[3-9]\\d{9}$", strValue);

            case "date":
                // 日期格式校验（支持自定义格式，默认yyyy-MM-dd）
                String pattern = param != null ? (String) param : "yyyy-MM-dd";
                SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                sdf.setLenient(false); // 严格模式（不允许2023-13-01等无效日期）
                try {
                    sdf.parse(strValue);
                    return true;
                } catch (ParseException e) {
                    return false;
                }

            case "boolean":
                // 布尔值校验（支持true/false字符串或Boolean对象）
                return value instanceof Boolean || "true".equals(strValue) || "false".equals(strValue);

            case "enum":
                // 枚举校验（值必须在param列表中，param为List）
                if (!(param instanceof List)) {
                    return false; // param不是列表则不通过
                }
                List<?> enumList = (List<?>) param;
                return enumList.contains(value);

            case "pattern":
                // 正则表达式校验
                if (!(param instanceof String)) {
                    return false; // param必须是正则字符串
                }
                return Pattern.matches((String) param, strValue);

            case "ipv4":
                // IPv4地址校验
                String ipv4Regex = "^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$";
                return Pattern.matches(ipv4Regex, strValue);

            case "url":
                // URL格式校验
                String urlRegex = "^(https?|ftp)://[^\\s/$.?#].[^\\s]*$";
                return Pattern.matches(urlRegex, strValue);

            case "trim":
                // 字符串无前后空格校验
                if (!(value instanceof String)) {
                    return false;
                }
                return ((String) value).trim().equals(value);

            default:
                throw new IllegalArgumentException("不支持的验证类型：" + type);
        }
    }

    /**
     * 统一验证类型格式（处理简写或拼写差异，如maxlen→maxLength）
     */
    private String normalizeValidatorType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("验证类型不能为空");
        }
        switch (type.toLowerCase()) {
            case "maxlen": return "maxLength";
            case "minlen": return "minLength";
            case "num": return "number";
            case "int": return "integer";
            default: return type.toLowerCase(); // 其他类型统一转为小写（如Required→required）
        }
    }

    /**
     * 生成默认错误消息
     */
    private String getDefaultMessage(String field, String type, Object param) {
        switch (type) {
            case "required": return field + "为必填项";
            case "number": return field + "必须为数字";
            case "integer": return field + "必须为整数";
            case "string": return field + "必须为字符串";
            case "min": return field + "不能小于" + param;
            case "max": return field + "不能大于" + param;
            case "minLength": return field + "长度不能小于" + param;
            case "maxLength": return field + "长度不能大于" + param;
            case "length": return field + "长度必须为" + param;
            case "email": return field + "格式不正确（需为合法邮箱）";
            case "phone": return field + "格式不正确（需为合法手机号）";
            case "date": return field + "格式不正确（默认格式yyyy-MM-dd，自定义格式：" + param + "）";
            case "boolean": return field + "必须为布尔值（true/false）";
            case "enum": return field + "必须为[" + param + "]中的值";
            case "pattern": return field + "格式不正确（不符合正则规则）";
            case "ipv4": return field + "必须为合法IPv4地址";
            case "url": return field + "必须为合法URL";
            case "trim": return field + "不能包含前后空格";
            default: return field + "验证失败（类型：" + type + "）";
        }
    }
}