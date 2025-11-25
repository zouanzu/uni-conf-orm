package com.ubi.orm.config;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SqlConfig {
    private String apiKey;
    private String tableName;
    private DbDrive dbDrive = new DbDrive();
    private String field = "*";
    private List<ParamMapping> paramsMapping;
    private Map<String, ConditionSchema> conditionSchema;
    private List<SortConfig> sort;
    private List<String> mutableFields;
    private String pk = "id";
    private AuthConfig authConfig; // 接口级安全配置

    @Data
    public static class DbDrive {
        private String drive = "mysql";
        private String host = "default";
    }

    @Data
    public static class ParamMapping {
        private String field;
        private String alias;
        private String source = "all";
        private Schema schema = new Schema();
    }

    @Data
    public static class Schema {
        private String type = "string";
        private Object defaultValue;
        private Integer min;
        private Integer max;
        private Integer minLength;
        private Integer maxLength;
        private List<Object> validValues;
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