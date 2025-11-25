package com.ubi.orm.core;

import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.validator.Joi;
import com.ubi.orm.validator.ParamsMapping;
import java.util.HashMap;
import java.util.Map;

/**
 * 参数解析器：将StandardParams转换为SQL生成所需的结构化参数
 */
public class ParamResolver {
    private final SqlConfig sqlConfig;

    public ParamResolver(SqlConfig sqlConfig) {
        this.sqlConfig = sqlConfig;
    }

    /**
     * 解析参数（处理默认值、类型转换）
     */
    public Map<String, Object> resolve(StandardParams standardParams) throws Exception {
        Map<String, Object> resolved = new HashMap<>();

        // 1. 处理配置的参数映射
        if (sqlConfig.getParamsMapping() != null) {
            for (ParamsMapping mapping : sqlConfig.getParamsMapping()) {
                String paramKey = mapping.getAlias() != null ? mapping.getAlias() : mapping.getField();
                Object value = getParamFromSource(standardParams, paramKey, mapping.getSource());
                Joi validator = new Joi(mapping);
                validator.validate(value);
                // 类型转换（简化版，实际可扩展更多类型）
                if (value != null) {
                    value = convertType(value, mapping.getDataType());
                }

                resolved.put(paramKey, value);
            }
        }

        // 2. 补充主键参数（用于更新/删除）
        String pk = sqlConfig.getPk();
        Object pkValue = getParamFromSource(standardParams, pk, "all");
        if (pkValue != null) {
            resolved.put(pk, pkValue);
        }
        // 3.补充action参数（用于确定是更新还是插入操作）
        String actField= sqlConfig.getAction();
        if (actField != null) {
           resolved.put("action",getParamFromSource(standardParams, actField, "all"));
        }
        return resolved;
    }

    /**
     * 从指定来源（path/query/body/all）获取参数
     */
    private Object getParamFromSource(StandardParams params, String field, String source) {
        switch (source.toLowerCase()) {
            case "path":
                return params.getPathParams().get(field);
            case "query":
                return params.getQueryParams().get(field);
            case "body":
                return params.getBodyParams().get(field);
            default: // all
                return params.getParam(field);
        }
    }

    /**
     * 类型转换（字符串参数转为配置的类型）
     */
    private Object convertType(Object value, String targetType) {
        if (value == null) return null;
        String strValue = value.toString();

        switch (targetType.toLowerCase()) {
            case "int":
                return Integer.parseInt(strValue);
            case "long":
                return Long.parseLong(strValue);
            case "double":
                return Double.parseDouble(strValue);
            case "boolean":
                return Boolean.parseBoolean(strValue);
            default: // string
                return strValue;
        }
    }
}
