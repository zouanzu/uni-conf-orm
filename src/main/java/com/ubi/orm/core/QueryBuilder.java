package com.ubi.orm.core;

import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.driver.DatabaseDriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL语句自动生成器：根据配置和参数动态构建SQL，支持多数据库语法适配
 */
public class QueryBuilder {
    private final SqlConfig sqlConfig;
    private final DatabaseDriver driver;

    // 运算符映射（配置中的运算符到SQL运算符）
    private static final Map<String, String> OPERATOR_MAPPING = Map.of(
            "=", "=",
            "!=", "<>",
            ">", ">",
            "<", "<",
            ">=", ">=",
            "<=", "<=",
            "like", "LIKE",
            "in", "IN",
            "notIn", "NOT IN"
    );

    public QueryBuilder(SqlConfig sqlConfig, DatabaseDriver driver) {
        this.sqlConfig = sqlConfig;
        this.driver = driver;
    }

    /**
     * 构建查询SQL（支持分页）
     * @param params 解析后的参数
     * @param isPage 是否分页
     * @param pageSize 每页条数
     * @param currentPage 当前页（从1开始）
     * @param sqlParams 输出参数列表（用于绑定到PreparedStatement）
     * @return 生成的SQL语句
     */
    public String buildQuerySql(Map<String, Object> params, boolean isPage,
                                int pageSize, int currentPage, List<Object> sqlParams) {
        // 1. 基础查询语句
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(sqlConfig.getField()).append(" FROM ").append(sqlConfig.getTableName());

        // 2. 构建WHERE子句
        String whereClause = buildWhereClause(params, sqlParams);
        if (!whereClause.isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        // 3. 构建排序
        String sortClause = buildSortClause();
        if (!sortClause.isEmpty()) {
            sql.append(" ").append(sortClause);
        }

        // 4. 构建分页（不同数据库语法适配）
        if (isPage) {
            int offset = (currentPage - 1) * pageSize;
            sql.append(" ").append(driver.buildPageSql("", pageSize, offset));
        }

        return sql.toString();
    }

    /**
     * 构建修改SQL（插入/更新/删除）
     * @param params 解析后的参数
     * @param isInsert 是否为插入操作（true=插入，false=更新/删除）
     * @param sqlParams 输出参数列表
     * @return 生成的SQL语句
     */
    public String buildModifySql(Map<String, Object> params, boolean isInsert, List<Object> sqlParams) {
        if (isInsert) {
            // 插入语句
            return buildInsertSql(params, sqlParams);
        } else {
            // 判断是否为删除（无修改字段则视为删除）
            Set<String> mutableFields = Set.of(sqlConfig.getMutableFields().toArray(new String[0]));
            boolean hasModifyFields = params.keySet().stream().anyMatch(mutableFields::contains);

            return hasModifyFields ?
                    buildUpdateSql(params, sqlParams) :
                    buildDeleteSql(params, sqlParams);
        }
    }

    /**
     * 构建WHERE子句（支持多条件组合）
     */
    public String buildWhereClause(Map<String, Object> params, List<Object> sqlParams) {
        if (sqlConfig.getConditionSchema() == null || sqlConfig.getConditionSchema().isEmpty()) {
            return "";
        }

        List<String> conditions = new ArrayList<>();
        // 遍历配置的条件规则
        for (Map.Entry<String, SqlConfig.ConditionSchema> entry : sqlConfig.getConditionSchema().entrySet()) {
            String paramKey = entry.getKey(); // 参数名（如"keyword"）
            SqlConfig.ConditionSchema condition = entry.getValue();
            Object paramValue = params.get(paramKey);

            // 参数不存在则跳过该条件
            if (paramValue == null) {
                continue;
            }

            // 处理多字段匹配（如keyword同时匹配username和email）
            List<String> fieldConditions = new ArrayList<>();
            for (String field : condition.getFields()) {
                String operator = OPERATOR_MAPPING.getOrDefault(condition.getOperator(), "=");
                String placeHolder = driver.getPlaceholder(sqlParams.size() + 1); // 占位符（如?或@p0）

                // 特殊处理LIKE（自动加通配符）
                if ("LIKE".equals(operator)) {
                    sqlParams.add("%" + paramValue + "%");
                    fieldConditions.add(field + " " + operator + " " + placeHolder);
                }
                // 特殊处理IN（参数需为列表）
                else if ("IN".equals(operator) || "NOT IN".equals(operator)) {
                    if (!(paramValue instanceof List)) {
                        throw new IllegalArgumentException("IN条件参数必须为列表: " + paramKey);
                    }
                    List<?> listValues = (List<?>) paramValue;
                    // 生成多个占位符（如?, ?, ?）
                    StringBuilder inPlaceholders = new StringBuilder();
                    for (int i = 0; i < listValues.size(); i++) {
                        inPlaceholders.append(driver.getPlaceholder(sqlParams.size() + 1));
                        sqlParams.add(listValues.get(i));
                        if (i < listValues.size() - 1) {
                            inPlaceholders.append(", ");
                        }
                    }
                    fieldConditions.add(field + " " + operator + " (" + inPlaceholders + ")");
                }
                // 普通运算符（=, >, <等）
                else {
                    sqlParams.add(paramValue);
                    fieldConditions.add(field + " " + operator + " " + placeHolder);
                }
            }

            // 组合同一参数的多字段条件（如username LIKE ? OR email LIKE ?）
            if (!fieldConditions.isEmpty()) {
                String logic = condition.getLogic().toUpperCase();
                conditions.add("(" + String.join(" " + logic + " ", fieldConditions) + ")");
            }
        }

        // 组合所有条件（AND连接）
        return conditions.isEmpty() ? "" : String.join(" AND ", conditions);
    }

    /**
     * 构建排序子句
     */
    private String buildSortClause() {
        if (sqlConfig.getSort() == null || sqlConfig.getSort().isEmpty()) {
            return "";
        }

        List<String> sorts = new ArrayList<>();
        for (SqlConfig.SortConfig sort : sqlConfig.getSort()) {
            sorts.add(sort.getField() + " " + sort.getOrder().toUpperCase());
        }
        return "ORDER BY " + String.join(", ", sorts);
    }

    /**
     * 构建插入SQL
     */
    private String buildInsertSql(Map<String, Object> params, List<Object> sqlParams) {
        List<String> fields = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        // 只处理可修改字段（mutableFields）
        for (String field : sqlConfig.getMutableFields()) {
            Object value = params.get(field);
            if (value != null) { // 允许字段为null，但参数不存在则不插入
                fields.add(field);
                placeholders.add(driver.getPlaceholder(sqlParams.size() + 1));
                sqlParams.add(value);
            }
        }

        if (fields.isEmpty()) {
            throw new IllegalArgumentException("插入操作至少需要一个字段");
        }

        return String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                sqlConfig.getTableName(),
                String.join(", ", fields),
                String.join(", ", placeholders)
        );
    }

    /**
     * 构建更新SQL
     */
    private String buildUpdateSql(Map<String, Object> params, List<Object> sqlParams) {
        List<String> setClauses = new ArrayList<>();

        // 构建SET子句（只处理可修改字段）
        for (String field : sqlConfig.getMutableFields()) {
            Object value = params.get(field);
            if (value != null) { // 跳过值为null的字段（如需更新为null需显式传值）
                String placeHolder = driver.getPlaceholder(sqlParams.size() + 1);
                setClauses.add(field + " = " + placeHolder);
                sqlParams.add(value);
            }
        }

        if (setClauses.isEmpty()) {
            throw new IllegalArgumentException("更新操作至少需要一个修改字段");
        }

        // 构建WHERE子句（基于主键）
        String pk = sqlConfig.getPk();
        Object pkValue = params.get(pk);
        if (pkValue == null) {
            throw new IllegalArgumentException("更新操作必须指定主键: " + pk);
        }
        String pkPlaceHolder = driver.getPlaceholder(sqlParams.size() + 1);
        sqlParams.add(pkValue);
        String whereClause = pk + " = " + pkPlaceHolder;

        return String.format(
                "UPDATE %s SET %s WHERE %s",
                sqlConfig.getTableName(),
                String.join(", ", setClauses),
                whereClause
        );
    }

    /**
     * 构建删除SQL
     */
    private String buildDeleteSql(Map<String, Object> params, List<Object> sqlParams) {
        // 基于主键删除
        String pk = sqlConfig.getPk();
        Object pkValue = params.get(pk);
        if (pkValue == null) {
            throw new IllegalArgumentException("删除操作必须指定主键: " + pk);
        }

        String pkPlaceHolder = driver.getPlaceholder(sqlParams.size() + 1);
        sqlParams.add(pkValue);
        String whereClause = pk + " = " + pkPlaceHolder;

        return String.format(
                "DELETE FROM %s WHERE %s",
                sqlConfig.getTableName(),
                whereClause
        );
    }
}
