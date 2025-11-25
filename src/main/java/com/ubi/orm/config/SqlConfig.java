package com.ubi.orm.config;

import com.ubi.orm.validator.ParamsMapping;
import com.ubi.orm.validator.Validator;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SqlConfig {
    private String apiKey;
    private String name;
    private String comments;
    private String author;
    private String department;
    private String group;
    private List<String> router_enabel;
    private String tableName;
    private DbDrive dbDrive = new DbDrive();
    private String field = "*";
    private List<ParamsMapping> paramsMapping;
    private Map<String, ConditionSchema> conditionSchema;
    private List<SortConfig> sort;
    private List<String> mutableFields;
    private String pk = "id";
    private AuthConfig authConfig; // 接口级安全配置
    private boolean requireAuth = false;//需要认证
    private int shallowToDeepThreshold=0;//定义翻到多少页，由浅分页过度到深分页
    private String action;
    private Map<String,Object>  presetParams;
    @Data
    public static class DbDrive {
        private String drive = "mysql";
        private String host = "default";
    }





    @Data
    public static class ConditionSchema {
        private List<String> fields;
        private String operator = "=";
        private String logic = "AND";
    }

    @Data
    public static class SortConfig {
        private String field;
        private String order = "asc";
    }
}