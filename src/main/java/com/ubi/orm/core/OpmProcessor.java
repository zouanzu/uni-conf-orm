package com.ubi.orm.core;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.config.ConfigManagers;
import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.driver.DatabaseConnection;
import com.ubi.orm.driver.ConnectionFactory;
import com.ubi.orm.driver.ExecuteSql;
import com.ubi.orm.monitor.AuditLogger;
import com.ubi.orm.monitor.SlowQueryLogger;
import com.ubi.orm.monitor.log.LogUtils;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.security.RateLimitException;
import com.ubi.orm.security.RateLimiter;
import com.ubi.orm.security.SignatureException;
import com.ubi.orm.security.SignatureValidator;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 操作处理器（OpmProcessor，单例模式）
 * <p>
 * 功能：统一处理数据库查询与修改操作，协调参数解析、SQL生成、安全验证（签名+限流）、
 * 日志监控（慢查询+审计）等全流程，支持多数据源适配与动态SQL执行。
 * 设计：单例模式确保全局唯一实例，避免资源重复初始化，提升系统处理效率。
 *
 * @author 邹安族
 * @date 2025年11月18日
 */
public class OpmProcessor {

    /**
     * 数据库操作类型枚举（替代硬编码字符串，避免拼写错误与扩展风险）
     *
     * @author 邹安族
     */
    public enum OperationType {
        MODIFY,   // 数据修改（INSERT/UPDATE/DELETE）
        LIST,     // 列表查询（无分页）
        PAGE,     // 分页查询
        DEEP_PAGE // 深度分页（适用于大数据量场景）
    }

    // 静态内部类实现单例（懒加载+线程安全，依赖JVM类加载机制保证实例唯一性）
    private static class SingletonHolder {
        private static final OpmProcessor INSTANCE = new OpmProcessor();
    }

    /** 配置管理器（单例依赖，线程安全，负责加载SQL/权限配置） */
    private final ConfigManagers configManager;

    /** SQL构建器（单例依赖，线程安全，负责动态生成SQL与参数绑定） */
    private final QueryBuilder queryBuilder;

    /** 连接工厂（单例依赖，线程安全，负责获取数据库连接） */
    private final ConnectionFactory connectionFactory;


    /**
     * 私有构造器：初始化核心依赖组件
     * <p>
     * 私有访问控制确保外部无法实例化，通过单例模式复用核心组件（配置管理器、连接工厂等），
     * 避免重复加载资源导致的性能损耗。
     *
     * @author 邹安族
     */
    private OpmProcessor() {
        this.configManager = ConfigManagers.getInstance();
        this.queryBuilder = QueryBuilder.getInstance();
        this.connectionFactory = ConnectionFactory.getInstance();
    }

    /**
     * 获取单例实例
     * <p>
     * 通过静态内部类实现懒加载，仅在首次调用时初始化实例，减少类加载阶段的资源占用。
     *
     * @return 全局唯一的OpmProcessor实例
     * @author 邹安族
     */
    public static OpmProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 处理数据库查询与修改操作
     * <p>
     * 核心流程：
     * 1. 配置校验（验证apiKey有效性，加载SQL与权限配置）
     * 2. 安全验证（签名校验+限流检查，基于权限配置）
     * 3. 参数解析（处理动态参数、默认值、类型转换）
     * 4. SQL生成（根据操作类型动态构建SQL与绑定参数）
     * 5. 数据库操作（执行查询/修改，自动处理结果集）
     * 6. 监控日志（慢查询记录+操作审计）
     *
     * @param apiKey           接口唯一标识（用于获取SQL配置与权限配置）
     * @param operation        操作类型（对应OperationType枚举，如"list"表示列表查询）
     * @param params           标准化参数（包含路径参数、查询参数、请求体参数）
     * @param clientFingerprint 客户端指纹（用于限流与审计，如IP+设备标识）
     * @return 统一响应结果：成功时包含数据（查询结果/影响行数），失败时包含错误信息
     * @author 邹安族
     */
    public Result<?> processQuery(String apiKey, String operation, StandardParams params, String clientFingerprint) {
        long startTime = System.currentTimeMillis(); // 记录操作开始时间（用于性能监控）
        ResultSet resultSet = null; // 结果集（需手动关闭，避免资源泄露）
        DatabaseConnection dbConn = null; // 数据库连接包装类
        Connection conn = null; // 数据库连接
        String auditString = null; // 审计日志字符串（签名验证结果）

        try {
            // 1. 校验apiKey并获取SQL配置
            SqlConfig sqlConfig = configManager.getSqlConfig(apiKey);
            if (sqlConfig == null) {
                return Result.fail("无效的apiKey：" + apiKey + "（未找到对应SQL配置）");
            }

            // 2. 获取权限配置
            AuthConfig authConfig = configManager.getEffectiveAuthConfig(sqlConfig.getAuthConfig());

            // 3. 安全验证流程
            Map<String, Object> mergedParams = mergeParams(params); // 合并所有参数
            // 3.1 签名验证（若配置要求）
            if (sqlConfig.isRequireAuth()) {
                try {
                    auditString = new SignatureValidator(authConfig).validate(mergedParams);
                } catch (SignatureException e) {
                    return Result.fail("签名验证失败：" + e.getMessage());
                }
            }
            // 3.2 限流检查（若配置了限流规则）
            if (authConfig != null && authConfig.getRateLimitMax() > 0 && authConfig.getRateLimitWindow() > 0) {
                try {
                    RateLimiter.getInstance().check(
                            apiKey,
                            clientFingerprint,
                            authConfig.getRateLimitMax(),
                            authConfig.getRateLimitWindow(),
                            authConfig.getIntervalMin()
                    );
                } catch (RateLimitException e) {
                    return Result.fail("触发限流：" + e.getMessage());
                }
            }

            // 4. 参数解析（处理动态参数、默认值、类型转换）
            ParamResolver paramResolver = new ParamResolver(sqlConfig);
            Map<String, Object> resolvedParams = paramResolver.resolve(params);
            if (resolvedParams == null) {
                return Result.fail("参数解析失败：无效的参数格式或缺失必填参数");
            }

            // 5. 生成SQL与绑定参数（根据操作类型）
            OperationType opType = parseOperationType(operation);
            Map<String, Object> sqlAndArgs = buildSqlAndArgs(sqlConfig, resolvedParams, opType);
            if (sqlAndArgs == null) {
                return Result.fail("SQL生成失败：不支持的操作类型[" + operation + "]");
            }
            String sql = (String) sqlAndArgs.get("sql");
            List<Object> args = (List<Object>) sqlAndArgs.get("args");

            // 6. 获取数据库连接
            dbConn = connectionFactory.getConnection(sqlConfig.getDbDrive().getDrive());
            conn = dbConn.getConnection(sqlConfig.getDbDrive().getHost());

            // 7. 执行数据库操作并处理结果
            ExecuteSql executeSql = new ExecuteSql();
            Result<?> result;
            if (opType == OperationType.MODIFY) {
                // 处理修改操作（INSERT/UPDATE/DELETE）
                Map<String, Object> modifyResult = executeSql.execute(sql, args, conn);
                int affectedRows = (int) modifyResult.get("affectedRows");
                Integer generatedKey = (Integer) modifyResult.get("generatedKey");
                result = Result.success(affectedRows, generatedKey);
            } else {
                // 处理查询操作（LIST/PAGE/DEEP_PAGE）
                resultSet = executeSql.query(sql, args, conn);
                List<Map<String, Object>> data = resultSetToMap(resultSet);
                result = Result.success(data);
            }

            // 8. 记录监控日志（慢查询+审计）
            long costTime = System.currentTimeMillis() - startTime;
            new SlowQueryLogger(authConfig).logIfSlow(sql, costTime, args.toArray());
            new AuditLogger().log(apiKey, auditString != null ? auditString : "未进行签名验证", costTime);

            return result;

        } catch (Exception e) {
            // 异常统一处理（记录日志并返回错误信息）
            LogUtils.coreError("操作失败：" + e.getMessage(), e);
            return Result.fail("操作失败：" + e.getMessage());
        } finally {
            // 强制释放资源（避免泄露）
            closeResultSet(resultSet);
            closeConnection(conn);
        }
    }

    /**
     * 解析操作类型字符串为枚举（避免硬编码判断）
     *
     * @param operation 操作类型字符串（如"list"、"modify"）
     * @return 对应的OperationType枚举
     * @throws IllegalArgumentException 当操作类型不支持时抛出
     * @author 邹安族
     */
    private OperationType parseOperationType(String operation) {
        try {
            return OperationType.valueOf(operation.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的操作类型：" + operation, e);
        }
    }

    /**
     * 构建SQL语句与绑定参数（根据操作类型）
     *
     * @param sqlConfig      SQL配置（包含SQL模板、参数映射等）
     * @param resolvedParams 解析后的参数Map
     * @param opType         操作类型枚举
     * @return 包含"sql"（SQL语句）和"args"（绑定参数列表）的Map
     * @author 邹安族
     */
    private Map<String, Object> buildSqlAndArgs(SqlConfig sqlConfig, Map<String, Object> resolvedParams, OperationType opType) {
        switch (opType) {
            case MODIFY:
                return queryBuilder.buildModify(sqlConfig, resolvedParams);
            case LIST:
                return queryBuilder.buildQuery(sqlConfig, resolvedParams);
            case PAGE:
                return queryBuilder.buildPage(sqlConfig, resolvedParams);
            case DEEP_PAGE:
                return queryBuilder.buildDeepPage(sqlConfig, resolvedParams);
            default:
                return null; // 理论上不会触发（parseOperationType已校验）
        }
    }

    /**
     * 合并多源参数（路径参数、查询参数、请求体参数）
     * <p>
     * 对可能为null的参数Map进行非null判断，避免NullPointerException；
     * 初始容量设为16（HashMap默认值），平衡哈希表性能与内存占用。
     *
     * @param params 标准化参数对象（包含path/query/body参数）
     * @return 合并后的参数Map（key=参数名，value=参数值）
     * @author 邹安族
     */
    private Map<String, Object> mergeParams(StandardParams params) {
        Map<String, Object> merged = new HashMap<>(16);
        // 路径参数（如/rest/{id}中的id）
        if (params.getPathParams() != null) {
            merged.putAll(params.getPathParams());
        }
        // 查询参数（如?name=test&page=1）
        if (params.getQueryParams() != null) {
            merged.putAll(params.getQueryParams());
        }
        // 请求体参数（如POST的JSON体）
        if (params.getBodyParams() != null) {
            merged.putAll(params.getBodyParams());
        }
        return merged;
    }

    /**
     * 将ResultSet转换为List<Map>（便于JSON序列化与前端展示）
     * <p>
     * 遍历结果集，将每一行数据转换为Map（key=列名，value=列值），最终封装为List返回。
     *
     * @param rs 数据库查询结果集
     * @return 转换后的列表（每条记录为一个Map）
     * @throws SQLException 结果集读取或元数据获取失败时抛出
     * @author 邹安族
     */
    private List<Map<String, Object>> resultSetToMap(ResultSet rs) throws SQLException {
        List<Map<String, Object>> resultList = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData(); // 获取列元数据（列名、类型等）
        int columnCount = metaData.getColumnCount();   // 列数量

        // 遍历结果集行
        while (rs.next()) {
            Map<String, Object> rowMap = new HashMap<>(columnCount); // 初始容量=列数，减少扩容
            // 遍历列
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i); // 列名（数据库字段名）
                Object columnValue = rs.getObject(i);          // 列值
                rowMap.put(columnName, columnValue);
            }
            resultList.add(rowMap);
        }
        return resultList;
    }

    /**
     * 关闭ResultSet资源（避免资源泄露）
     *
     * @param rs 待关闭的结果集（可为null）
     * @author 邹安族
     */
    private void closeResultSet(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                LogUtils.coreWarn("结果集关闭失败：" + e.getMessage());
            }
        }
    }

    /**
     * 关闭数据库连接（避免连接泄露）
     *
     * @param conn 待关闭的连接（可为null）
     * @author 邹安族
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                LogUtils.coreWarn("数据库连接关闭失败：" + e.getMessage());
            }
        }
    }


}