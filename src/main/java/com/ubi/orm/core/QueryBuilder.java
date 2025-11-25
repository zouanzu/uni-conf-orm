package com.ubi.orm.core;
import com.ubi.orm.config.SqlConfig;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SQL构建器（单例模式）
 * 核心功能：生成跨数据库兼容的CRUD SQL语句，支持MySQL、MSSQL、SQLite等数据库
 * 支持动态条件拼接、分页（浅分页/深分页自动切换）、排序等功能
 *
 * @author 邹安族
 * @since 2025-10-1
 */
public class QueryBuilder {
    // 静态内部类实现单例模式（线程安全且懒加载）
    private static class SingletonHolder {
        private static final QueryBuilder INSTANCE = new QueryBuilder();
    }

    /**
     * 获取单例实例
     * @return QueryBuilder唯一实例
     */
    public static QueryBuilder getInstance() {
        return SingletonHolder.INSTANCE;
    }

    // 私有构造器防止外部实例化
    private QueryBuilder() {}


    /**
     * 上下文类：存储单次SQL构建的上下文信息
     * 包含数据库驱动类型、参数索引（用于生成占位符）、大数组阈值（优化IN查询）
     */
    private static class Context {
        String driveType; // 数据库驱动类型（如mysql、mssql、sqlite）
        int paramIndex; // 参数索引，用于生成有序占位符（如MSSQL的@p0、@p1）
        int largeArrayThreshold; // 大数组阈值，超过此值时提示性能优化（针对MSSQL）

        /**
         * 初始化上下文
         * @param driveType 数据库驱动类型
         */
        public Context(String driveType) {
            this.driveType = driveType;
            this.paramIndex = 0; // 从0开始计数
            this.largeArrayThreshold = 1000; // 默认阈值1000
        }
    }


    /**
     * 操作符处理器接口：定义操作符处理逻辑的标准
     * 用于统一处理不同SQL操作符（如=、>、IN等）的SQL拼接和参数绑定
     */
    @FunctionalInterface
    private interface OperatorHandler {
        /**
         * 处理操作符逻辑
         * @param context 上下文信息
         * @param options 处理参数（包含字段名、操作符、参数值等）
         * @param clauses 存储生成的SQL子句
         * @param args 存储绑定的参数值
         */
        void handle(Context context, Map<String, Object> options, List<String> clauses, List<Object> args);
    }

    /**
     * 操作符处理映射表：key为操作符（小写），value为对应的处理逻辑
     * 覆盖所有支持的SQL操作符，实现跨数据库的条件拼接
     */
    private final Map<String, OperatorHandler> operatorHandlers = new HashMap<String, OperatorHandler>() {{
        // 基础比较操作符：=
        put("=", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s = %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：>
        put(">", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s > %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：<
        put("<", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s < %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：>=
        put(">=", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s >= %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：<=
        put("<=", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s <= %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：!=
        put("!=", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s != %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 基础比较操作符：<>（与!=等价，部分数据库偏好）
        put("<>", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s <> %s", opts.get("field"), placeholder));
            args.add(opts.get("paramValue"));
        });

        // 模糊匹配：LIKE（自动拼接%通配符）
        put("like", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s LIKE %s", opts.get("field"), placeholder));
            args.add("%" + opts.get("paramValue") + "%"); // 前后加%实现全模糊匹配
        });

        // 模糊匹配：NOT LIKE
        put("not like", (ctx, opts, clauses, args) -> {
            String placeholder = getPlaceholder(ctx);
            clauses.add(String.format("%s NOT LIKE %s", opts.get("field"), placeholder));
            args.add("%" + opts.get("paramValue") + "%");
        });

        // 集合匹配：IN（支持数组或逗号分隔字符串参数）
        put("in", (ctx, opts, clauses, args) -> {
            Object paramValue = opts.get("paramValue");
            List<Object> parsedArray = parseArrayParam(paramValue); // 解析参数为数组
            if (parsedArray.isEmpty()) {
                clauses.add("1=0"); // 空集合匹配：结果恒为假
                return;
            }
            // MSSQL对大数组IN查询性能提示
            if ("mssql".equals(ctx.driveType) && parsedArray.size() > ctx.largeArrayThreshold) {
                System.out.println("WARN: IN with " + parsedArray.size() + " elements: use TVP for better performance");
            }
            // 生成多个占位符（如?1,?2或@p0,@p1）
            String placeholders = parsedArray.stream()
                    .map(v -> getPlaceholder(ctx))
                    .collect(Collectors.joining(", "));
            clauses.add(String.format("%s IN (%s)", opts.get("field"), placeholders));
            args.addAll(parsedArray); // 绑定数组参数
        });

        // 集合匹配：NOT IN
        put("not in", (ctx, opts, clauses, args) -> {
            Object paramValue = opts.get("paramValue");
            List<Object> parsedArray = parseArrayParam(paramValue);
            if (parsedArray.isEmpty()) {
                clauses.add("1=1"); // 空集合匹配：结果恒为真
                return;
            }
            String placeholders = parsedArray.stream()
                    .map(v -> getPlaceholder(ctx))
                    .collect(Collectors.joining(", "));
            clauses.add(String.format("%s NOT IN (%s)", opts.get("field"), placeholders));
            args.addAll(parsedArray);
        });

        // 范围匹配：BETWEEN（要求参数为长度2的数组）
        put("between", (ctx, opts, clauses, args) -> {
            Object paramValue = opts.get("paramValue");
            List<Object> parsedArray = parseArrayParam(paramValue);
            if (parsedArray.size() != 2) {
                throw new IllegalArgumentException("BETWEEN requires array of length 2, got " + paramValue);
            }
            String p1 = getPlaceholder(ctx);
            String p2 = getPlaceholder(ctx);
            clauses.add(String.format("%s BETWEEN %s AND %s", opts.get("field"), p1, p2));
            args.add(parsedArray.get(0)); // 范围起始值
            args.add(parsedArray.get(1)); // 范围结束值
        });

        // 范围匹配：NOT BETWEEN
        put("not between", (ctx, opts, clauses, args) -> {
            Object paramValue = opts.get("paramValue");
            List<Object> parsedArray = parseArrayParam(paramValue);
            if (parsedArray.size() != 2) {
                throw new IllegalArgumentException("NOT BETWEEN requires array of length 2, got " + paramValue);
            }
            String p1 = getPlaceholder(ctx);
            String p2 = getPlaceholder(ctx);
            clauses.add(String.format("%s NOT BETWEEN %s AND %s", opts.get("field"), p1, p2));
            args.add(parsedArray.get(0));
            args.add(parsedArray.get(1));
        });

        // 空值判断：IS NULL（无需参数）
        put("is null", (ctx, opts, clauses, args) -> {
            clauses.add(String.format("%s IS NULL", opts.get("field")));
        });

        // 空值判断：IS NOT NULL（无需参数）
        put("is not null", (ctx, opts, clauses, args) -> {
            clauses.add(String.format("%s IS NOT NULL", opts.get("field")));
        });
    }};


    /**
     * 构建查询SQL（SELECT语句，支持列表查询）
     * 核心逻辑：拼接SELECT字段、表名、WHERE条件、排序，不包含分页
     *
     * @param sqlConfig 实体配置，包含：
     *                  - tableName：表名
     *                  - field：查询字段（默认*）
     *                  - conditionSchema：条件配置
     *                  - sort：排序配置
     *                  - dbDrive：数据库驱动配置（含drive类型）
     * @param params 用户输入参数（包含查询条件、排序参数等）
     * @return 包含生成的SQL字符串和参数列表的Map，格式：
     *         { "sql": "SELECT ...", "args": [param1, param2, ...] }
     */
    public Map<String, Object> buildQuery(SqlConfig sqlConfig, Map<String, Object> params) {
        try {
            // 创建上下文（包含驱动类型等信息）
            Context context = createContext(sqlConfig);
            // 提取表名和查询字段（默认查询所有字段）
            String tableName = (String) sqlConfig.getTableName();
            String field = sqlConfig.getField() != null ? (String) sqlConfig.getField() : "*";

            // 构建排序SQL（如ORDER BY id DESC）
            String sortSql = buildSort(sqlConfig);
            // 构建WHERE条件（如WHERE name = ? AND age > ?）
            Map<String, Object> whereResult = buildWhere(context, sqlConfig, params);

            // 处理max_total：创建分页参数副本，避免修改原参数
            Map<String, Object> pageParams = new HashMap<>(params);
            if (!isEmpty(pageParams.get("max_total"))) {
                pageParams.put("current_page", 1); // 强制第一页
                pageParams.put("page_size", pageParams.get("max_total")); // 页大小设为max_total
            }

            // 拼接SQL语句
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ").append(field)
                    .append(" FROM ").append(tableName)
                    .append(" ").append(whereResult.get("sql")) // 拼接WHERE条件
                    .append(" ").append(sortSql); // 拼接排序

            // 去除多余空格，格式化SQL
            String trimmedSql = sql.toString().replaceAll("\\s+", " ").trim();

            // 封装结果
            Map<String, Object> result = new HashMap<>();
            result.put("sql", trimmedSql);
            result.put("args", whereResult.get("args")); // 绑定WHERE条件的参数
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build query SQL", e);
        }
    }


    /**
     * 构建分页查询SQL（支持浅分页/深分页自动切换）
     * 浅分页：基于LIMIT/OFFSET（MySQL/SQLite）或OFFSET...FETCH（MSSQL）
     * 深分页：当current_page超过阈值时，自动切换为ROW_NUMBER()窗口函数实现（优化性能）
     *
     * @param sqlConfig 实体配置，额外包含：
     *                  - shallowToDeepThreshold：浅分页转深分页的阈值（默认0，即不切换）
     * @param params 用户输入参数，包含：
     *               - current_page：当前页码（默认1）
     *               - page_size：页大小（默认10）
     *               - max_total：最大返回总数（可选，用于限制TotalCount）
     * @return 包含生成的SQL字符串和参数列表的Map
     */
    public Map<String, Object> buildPage(SqlConfig sqlConfig, Map<String, Object> params) {
        try {
            Context context = createContext(sqlConfig);
            String tableName = (String) sqlConfig.getTableName();
            String field = sqlConfig.getField() != null ? (String) sqlConfig.getField() : "*";
            // 浅分页转深分页的阈值（默认0，即始终使用浅分页）
            int shallowToDeepThreshold = sqlConfig.getShallowToDeepThreshold();

            // 当前页码（默认1，从1开始计数）
            int currentPage = params.get("current_page") != null ? (Integer) params.get("current_page") : 1;

            // 深分页判断：当前页超过阈值时，调用深分页构建方法
            if (shallowToDeepThreshold > 0 && currentPage > shallowToDeepThreshold) {
                return buildDeepPage(sqlConfig, params);
            }

            // 构建WHERE条件和排序
            Map<String, Object> whereResult = buildWhere(context, sqlConfig, params);
            String sortSql = buildSort(sqlConfig);
            // 构建浅分页SQL（如LIMIT 10 OFFSET 20）
            String limitSql = buildLimit(context, params, null);

            // 处理TotalCount（总条数）：支持通过max_total限制显示的总数
            List<Object> args = new ArrayList<>((List<Object>) whereResult.get("args"));
            String totalCountExpr;

            if (!isEmpty(params.get("max_total"))) {
                // 当总数超过max_total时，显示max_total；否则显示实际总数
                totalCountExpr = String.format(
                        "CASE WHEN COUNT(*) OVER () > %s THEN %s ELSE COUNT(*) OVER () END AS TotalCount",
                        getPlaceholder(context),
                        getPlaceholder(context)
                );
                args.add(params.get("max_total")); // 绑定max_total参数（两个占位符共用）
                args.add(params.get("max_total"));
            } else {
                // 无max_total时，直接查询总条数
                totalCountExpr = "COUNT(*) OVER () AS TotalCount";
            }

            // 拼接SQL（使用CTE临时表包装结果，同时查询数据和总条数）
            StringBuilder sql = new StringBuilder();
            sql.append("WITH all_rows AS ( ")
                    .append("SELECT ").append(field).append(", ").append(totalCountExpr)
                    .append(" FROM ").append(tableName)
                    .append(" ").append(whereResult.get("sql"))
                    .append(" ").append(sortSql)
                    .append(" ) ")
                    .append("SELECT * FROM all_rows ")
                    .append(limitSql);

            String trimmedSql = sql.toString().replaceAll("\\s+", " ").trim();

            Map<String, Object> result = new HashMap<>();
            result.put("sql", trimmedSql);
            result.put("args", args);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build page SQL", e);
        }
    }


    /**
     * 构建深分页SQL（基于窗口函数ROW_NUMBER()）
     * 适用于大页码场景（如第1000页以后），性能优于浅分页的OFFSET
     *
     * @param sqlConfig 实体配置（必须包含sort排序配置，否则抛异常）
     * @param params 用户输入参数（同buildPage）
     * @return 包含生成的SQL字符串和参数列表的Map
     */
    public Map<String, Object> buildDeepPage(SqlConfig sqlConfig, Map<String, Object> params) {
        try {
            Context context = createContext(sqlConfig);
            String tableName = (String) sqlConfig.getTableName();
            String field = sqlConfig.getField() != null ? (String) sqlConfig.getField() : "*";

            // 构建WHERE条件和排序
            Map<String, Object> whereResult = buildWhere(context, sqlConfig, params);
            String sortSql = buildSort(sqlConfig);

            // 强制检查排序配置：ROW_NUMBER()必须依赖ORDER BY，否则抛异常
            if (isEmpty(sortSql)) {
                throw new RuntimeException("Deep pagination requires 'sort' configuration in sqlConfig name: " + sqlConfig.getDbDrive().getDrive());
            }

            // 构建深分页的范围条件（如rn BETWEEN 201 AND 300）
            String limitSql = buildLimit(context, params, "between");
            // 总条数查询（固定逻辑，不支持max_total，因深分页场景通常不需要限制总数）
            String totalCountExpr = "COUNT(*) OVER () AS TotalCount";

            // 绑定参数（复用WHERE条件的参数）
            List<Object> args = new ArrayList<>((List<Object>) whereResult.get("args"));

            // 拼接SQL（使用子查询生成行号，外层过滤行号范围）
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT * FROM ( ")
                    .append("SELECT ").append(field)
                    .append(", ROW_NUMBER() OVER (").append(sortSql).append(") AS rn ") // 生成行号
                    .append(", ").append(totalCountExpr)
                    .append(" FROM ").append(tableName)
                    .append(" ").append(whereResult.get("sql"))
                    .append(" ) AS numbered_rows ")
                    .append("WHERE rn ").append(limitSql); // 过滤行号范围

            String trimmedSql = sql.toString().replaceAll("\\s+", " ").trim();

            Map<String, Object> result = new HashMap<>();
            result.put("sql", trimmedSql);
            result.put("args", args);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build deep page SQL", e);
        }
    }


    /**
     * 构建新增/修改SQL（自动判断INSERT/UPDATE）
     * 核心逻辑：
     * - 有修改条件（主键或conditionSchema）则生成UPDATE
     * - 无修改条件则生成INSERT
     *
     * @param sqlConfig 实体配置，包含：
     *                  - tableName：表名
     *                  - mutableFields：可修改字段列表
     *                  - pk：主键字段（默认id）
     *                  - conditionSchema：更新条件配置（可选）
     *                  - action：操作类型字段配置（可选，用于手动指定update）
     * @param params 用户输入参数，包含：
     *               - 字段值（与mutableFields对应）
     *               - 主键值（用于UPDATE条件）
     *               - 操作类型参数（如action=update，可选）
     * @return 包含生成的SQL字符串和参数列表的Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildModify(SqlConfig sqlConfig, Map<String, Object> params) {
        try {
            Context context = createContext(sqlConfig);
            String tableName = (String) sqlConfig.getTableName();
            List<String> mutableFields = (List<String>) sqlConfig.getMutableFields(); // 可修改字段
            String pk = sqlConfig.getPk() != null ? (String) sqlConfig.getPk() : "id"; // 主键字段
            Map<String, SqlConfig.ConditionSchema> conditionSchema = (Map<String, SqlConfig.ConditionSchema>) sqlConfig.getConditionSchema(); // 更新条件配置

            // 提取可修改字段的数据（过滤非mutableFields的参数）
            Map<String, Object> data = extractMutableData(sqlConfig, params);
            Object pkValue = params.get(pk); // 主键值（用于判断是否为更新）
            // 操作类型字段（如action=update，用于手动指定操作类型）
            String actField = sqlConfig.getAction() != null ? sqlConfig.getAction() : null;
            String action = actField != null ? params.get(actField.toString()).toString() : null;

            // 预生成条件（用于判断是否为修改操作）
            // 1. 基于conditionSchema生成的WHERE条件
            Map<String, Object> whereResult = conditionSchema != null
                    ? buildWhere(context, sqlConfig, params)
                    : new HashMap<String, Object>() {{ put("sql", ""); put("args", new ArrayList<>()); }};
            boolean hasConditionFromSchema = !((String) whereResult.get("sql")).trim().isEmpty(); // 是否有有效的条件规则

            // 2. 主键条件（是否有主键值）
            boolean hasPkCondition = !isEmpty(pkValue);

            // 判断操作类型：修改必须有条件（主键或conditionSchema），否则视为插入
            boolean isUpdate = (isEmpty(action) && !isEmpty(pkValue))
                    || ("update".equals(action) && (hasConditionFromSchema || !isEmpty(pkValue)));

            // 插入操作（无任何修改条件）
            if (!isUpdate) {
                // 筛选有值的可修改字段
                List<String> fields = mutableFields.stream()
                        .filter(field -> data.containsKey(field))
                        .collect(Collectors.toList());

                // 处理主键在可修改字段中的情况：若未传入主键值但参数中有，则补充
                boolean pkInMutable = mutableFields.contains(pk);
                if (pkInMutable && !data.containsKey(pk) && !isEmpty(pkValue)) {
                    fields.add(pk); // 补充主键字段
                }

                // 校验：不允许插入空记录
                if (fields.isEmpty()) {
                    throw new RuntimeException("insert into 不允许插入空白记录");
                }

                // 生成INSERT语句的占位符（如?,?或@p0,@p1）
                String placeholders = fields.stream()
                        .map(f -> getPlaceholder(context))
                        .collect(Collectors.joining(", "));

                // 拼接INSERT SQL
                String sql = String.format(
                        "INSERT INTO %s (%s) VALUES (%s)",
                        tableName,
                        String.join(", ", fields), // 字段列表
                        placeholders // 值占位符
                );

                // 绑定参数（主键值可能来自params而非data）
                List<Object> args = fields.stream()
                        .map(field -> field.equals(pk) && !data.containsKey(pk) ? pkValue : data.get(field))
                        .collect(Collectors.toList());

                Map<String, Object> result = new HashMap<>();
                result.put("sql", sql);
                result.put("args", args);
                return result;
            }

            // 更新操作（有条件）
            // 生成SET子句（如name = ?, age = ?）
            List<String> setClauses = mutableFields.stream()
                    .filter(field -> data.containsKey(field) && !field.equals(pk)) // 排除主键（通常不更新主键）
                    .map(field -> String.format("%s = %s", field, getPlaceholder(context)))
                    .collect(Collectors.toList());

            // 校验：必须有要更新的字段
            if (setClauses.isEmpty()) {
                throw new RuntimeException("No mutable fields provided for update operation,没有提供要修改的字段,配置名称是：" + sqlConfig.getDbDrive().getDrive());
            }

            // 绑定SET子句的参数
            List<Object> args = mutableFields.stream()
                    .filter(field -> data.containsKey(field))
                    .map(field -> data.get(field))
                    .collect(Collectors.toList());

            // 构建WHERE条件（优先使用conditionSchema，其次使用主键）
            String whereSql;
            if (hasConditionFromSchema) {
                whereSql = (String) whereResult.get("sql");
                args.addAll((List<Object>) whereResult.get("args")); // 追加条件参数
            } else if (!isEmpty(pkValue)) {
                // 无conditionSchema时，使用主键作为条件（如WHERE id = ?）
                whereSql = " WHERE " + pk + " = " + getPlaceholder(context);
                args.add(pkValue); // 绑定主键值
            } else {
                // 无任何条件时，禁止全表更新（安全校验）
                throw new RuntimeException("没有有效的过滤条件，不允许全表更新," + sqlConfig.getDbDrive().getDrive());
            }

            // 拼接UPDATE SQL
            String sql = String.format(
                    "UPDATE %s SET %s %s",
                    tableName,
                    String.join(", ", setClauses), // SET子句
                    whereSql // WHERE条件
            );

            Map<String, Object> result = new HashMap<>();
            result.put("sql", sql);
            result.put("args", args);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build modify SQL", e);
        }
    }


    /**
     * 创建单次SQL构建的上下文
     * 上下文包含数据库驱动类型、参数索引等，确保单次构建过程中状态正确
     *
     * @param sqlConfig 实体配置（必须包含dbDrive.drive）
     * @return 初始化后的上下文对象
     * @throws RuntimeException 若未配置dbDrive.drive则抛异常
     */
    private Context createContext(SqlConfig sqlConfig) {
        SqlConfig.DbDrive dbDrive = (SqlConfig.DbDrive) sqlConfig.getDbDrive();
        if (dbDrive == null || dbDrive.getDrive() == null) {
            throw new RuntimeException("sqlConfig.dbDrive.drive is required (e.g., mysql, mssql),没有提供数据驱动的类型，配置名称是：" + sqlConfig.getDbDrive().getDrive());
        }
        return new Context((String) dbDrive.getDrive());
    }


    /**
     * 构建WHERE条件SQL
     * 根据conditionSchema解析参数，生成跨数据库兼容的条件子句
     *
     * @param context 上下文信息
     * @param sqlConfig 实体配置（包含conditionSchema）
     * @param params 用户输入参数（包含条件值）
     * @return 包含WHERE子句和参数列表的Map，格式：
     *         { "sql": "WHERE ...", "args": [param1, ...] }（无where条件时sql为空）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildWhere(Context context, SqlConfig sqlConfig, Map<String, Object> params) {
        Map<String, SqlConfig.ConditionSchema> conditionSchema = (Map<String, SqlConfig.ConditionSchema>) sqlConfig.getConditionSchema();
        if (conditionSchema == null) {
            return new HashMap<String, Object>() {{ put("sql", ""); put("args", new ArrayList<>()); }};
        }

        // 合并所有参数（避免修改原参数）
        Map<String, Object> allParams = new HashMap<>(params);
        List<String> whereClauses = new ArrayList<>(); // 存储条件子句
        List<Object> args = new ArrayList<>(); // 存储条件参数

        // 遍历conditionSchema，解析每个条件
        for (Map.Entry<String, SqlConfig.ConditionSchema> entry : conditionSchema.entrySet()) {
            String key = entry.getKey(); // 参数名（与params中的key对应）
            Map<String, Object> condition = (Map<String, Object>) entry.getValue(); // 条件配置
            Object paramValue = allParams.get(key); // 参数值（可能为null/未传）

            // 跳过未传值的条件
            if (paramValue == null) continue;

            // 解析条件配置：字段列表、操作符、逻辑关系（AND/OR）
            List<String> fields = (List<String>) condition.get("fields"); // 要匹配的字段列表
            String operator = (String) condition.get("operator"); // 操作符（如=、like）
            String logic = condition.get("logic") != null ? (String) condition.get("logic") : "AND"; // 多字段间的逻辑关系
            boolean isOrLogic = "OR".equalsIgnoreCase(logic);

            // 生成当前条件的子句（可能包含多个字段的条件）
            List<String> clauses = new ArrayList<>();
            for (String field : fields) {
                // 封装处理参数：字段名、操作符、参数值
                Map<String, Object> options = new HashMap<>();
                options.put("field", field);
                options.put("operator", operator);
                options.put("paramValue", paramValue);
                // 调用对应操作符的处理器
                handleOperator(context, options, clauses, args);
            }

            // 合并当前条件的子句（多字段用AND/OR连接）
            if (!clauses.isEmpty()) {
                String combined = isOrLogic
                        ? "(" + String.join(" OR ", clauses) + ")" // OR逻辑需用括号包裹
                        : String.join(" AND ", clauses); // AND逻辑直接连接
                whereClauses.add(combined);
            }
        }

        // 拼接最终的WHERE子句（所有条件用AND连接）
        String sql = whereClauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", whereClauses);
        Map<String, Object> result = new HashMap<>();
        result.put("sql", sql);
        result.put("args", args);
        return result;
    }


    /**
     * 解析数组参数（支持数组或逗号分隔字符串）
     * 将输入参数标准化为List<Object>，方便统一处理IN、BETWEEN等操作符
     *
     * @param paramValue 输入参数（可能为String、List或其他类型）
     * @return 标准化后的List<Object>
     */
    private List<Object> parseArrayParam(Object paramValue) {
        List<Object> result = new ArrayList<>();

        if (paramValue instanceof String) {
            // 逗号分隔字符串：按逗号拆分并转换类型（如"1,2,3" → [1,2,3]）
            String[] parts = ((String) paramValue).split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                result.add(parseValue(trimmed)); // 尝试转换为数字
            }
        } else if (paramValue instanceof List) {
            // 列表：直接转换元素类型
            ((List<?>) paramValue).forEach(item -> result.add(parseValue(item)));
        } else {
            // 其他类型：直接作为单元素列表
            result.add(paramValue);
        }

        return result;
    }

    /**
     * 解析单个值（字符串尝试转为数字）
     * 支持将字符串形式的数字（如"123"）转为Integer/Double，非数字保持String
     *
     * @param value 输入值
     * @return 转换后的值（数字或原字符串）
     */
    private Object parseValue(Object value) {
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value); // 尝试转为整数
            } catch (NumberFormatException e1) {
                try {
                    return Double.parseDouble((String) value); // 尝试转为浮点数
                } catch (NumberFormatException e2) {
                    return value; // 非数字，返回原字符串
                }
            }
        }
        return value; // 非字符串，直接返回
    }


    /**
     * 分发操作符处理逻辑
     * 根据操作符查找对应的处理器，并执行SQL子句生成和参数绑定
     *
     * @param context 上下文信息
     * @param options 处理参数（包含字段名、操作符、参数值等）
     * @param clauses 存储生成的SQL子句
     * @param args 存储绑定的参数值
     * @throws IllegalArgumentException 若操作符不支持则抛异常
     */
    private void handleOperator(Context context, Map<String, Object> options, List<String> clauses, List<Object> args) {
        String operator = (String) options.get("operator");
        String op = operator.toLowerCase(); // 统一转为小写，避免大小写问题

        OperatorHandler handler = operatorHandlers.get(op);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported operator: " + operator
                    + ". Supported: " + String.join(", ", operatorHandlers.keySet()));
        }

        // 执行处理器逻辑
        handler.handle(context, options, clauses, args);
    }


    /**
     * 生成参数占位符（跨数据库兼容）
     * - MySQL/SQLite使用?
     * - MSSQL使用@p0、@p1等带索引的占位符
     *
     * @param context 上下文信息（包含驱动类型和参数索引）
     * @return 生成的占位符字符串
     */
    private String getPlaceholder(Context context) {
        if ("mssql".equals(context.driveType)) {
            String placeholder = "@p" + context.paramIndex;
            context.paramIndex++; // 索引自增，确保唯一
            return placeholder;
        } else {
            return "?"; // 其他数据库默认使用?
        }
    }


    /**
     * 构建排序SQL（ORDER BY子句）
     *
     * @param sqlConfig 实体配置，包含sort列表，格式：
     *                  [ { "field": "id", "direction": "desc" }, ... ]
     * @return 排序子句（如"ORDER BY id DESC, name ASC"），无排序时返回空字符串
     */
    @SuppressWarnings("unchecked")
    private String buildSort(SqlConfig sqlConfig) {
        List<SqlConfig.SortConfig> sort = (List<SqlConfig.SortConfig>) sqlConfig.getSort();
        if (sort == null || sort.isEmpty()) {
            return ""; // 无排序配置
        }

        // 生成排序子句（如"id DESC, name ASC"）
        List<String> sortClauses = sort.stream()
                .map(item -> {
                    String field = (String) item.getField(); // 排序字段
                    String direction = (String) item.getOrder(); // 排序方向（asc/desc）
                    return field + " " + direction.toUpperCase(); // 统一转为大写（ASC/DESC）
                })
                .collect(Collectors.toList());

        return "ORDER BY " + String.join(", ", sortClauses);
    }


    /**
     * 构建分页SQL（LIMIT/OFFSET或ROW_NUMBER范围）
     *
     * @param context 上下文信息
     * @param params 分页参数（current_page、page_size）
     * @param type 分页类型：null表示浅分页，"between"表示深分页的行号范围
     * @return 分页子句（如"LIMIT 10 OFFSET 20"或"BETWEEN 21 AND 30"）
     */
    private String buildLimit(Context context, Map<String, Object> params, String type) {
        // 解析分页参数（默认当前页1，页大小10）
        int currentPage = params.get("current_page") != null ? (Integer) params.get("current_page") : 1;
        int pageSize = params.get("page_size") != null ? (Integer) params.get("page_size") : 10;
        // 计算偏移量（确保非负）
        int offset = Math.max((currentPage - 1) * pageSize, 0);

        // 深分页：返回行号范围（如BETWEEN 21 AND 30）
        if ("between".equals(type)) {
            return String.format("BETWEEN %d AND %d", offset + 1, offset + pageSize);
        }

        // 浅分页：根据数据库类型生成不同的分页语法
        switch (context.driveType) {
            case "mysql":
            case "sqlite":
                return String.format("LIMIT %d OFFSET %d", pageSize, offset);
            case "mssql":
                return String.format("OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", offset, pageSize);
            default:
                return String.format("LIMIT %d OFFSET %d", pageSize, offset); // 默认使用MySQL语法
        }
    }


    /**
     * 提取可修改字段的数据
     * 从params中筛选出mutableFields包含的字段，确保只处理允许修改的字段
     *
     * @param sqlConfig 实体配置（包含mutableFields）
     * @param params 用户输入参数
     * @return 过滤后的字段-值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMutableData(SqlConfig sqlConfig, Map<String, Object> params) {
        List<String> mutableFields = (List<String>) sqlConfig.getMutableFields();
        if (mutableFields == null) {
            return new HashMap<>(); // 无配置时返回空
        }

        // 筛选params中包含在mutableFields中的字段
        Map<String, Object> result = new HashMap<>();
        for (String field : mutableFields) {
            if (params.containsKey(field)) {
                result.put(field, params.get(field));
            }
        }
        return result;
    }


    /**
     * 工具方法：判断对象是否为空
     * 支持判断null、空字符串、空集合、空Map
     *
     * @param value 待判断的对象
     * @return true表示为空，false表示非空
     */
    private boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).trim().isEmpty(); // 空字符串（含空格）
        if (value instanceof Collection) return ((Collection<?>) value).isEmpty(); // 空集合
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty(); // 空Map
        return false;
    }
}