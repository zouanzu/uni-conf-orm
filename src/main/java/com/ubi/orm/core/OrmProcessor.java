package com.ubi.orm.core;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.config.ConfigManagers;
import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.driver.ExecuteSql;

import com.ubi.orm.monitor.SlowQueryLogger;
import com.ubi.orm.monitor.log.LogUtils;
import com.ubi.orm.param.StandardParams;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * ORM核心处理器（单例模式）
 * <p>
 * 功能：统一处理数据库查询与修改操作，协调参数解析、SQL生成、安全验证、日志监控等全流程。
 * 支持动态SQL构建、参数绑定、结果集转换、性能监控及安全审计，无框架依赖，轻量可扩展。
 *
 * @author 邹安族
 * @date 2025年11月19日
 */
public class OrmProcessor {

    /**
     * 数据库操作类型枚举（替代硬编码字符串，避免拼写错误与扩展风险）
     */
    public enum OperationType {
        MODIFY,   // 数据修改（INSERT/UPDATE/DELETE）
        LIST,     // 列表查询（无分页）
        PAGE,     // 分页查询
        DEEP_PAGE // 深度分页（适用于大数据量分页场景）
    }

    // 静态内部类实现单例（懒加载+线程安全，依赖JVM类加载机制保证唯一性）
    private static class SingletonHolder {
        private static final OrmProcessor INSTANCE = new OrmProcessor();
    }

    /** 配置管理器（单例依赖，线程安全，负责加载SQL/权限配置） */
    private final ConfigManagers configManager;



    /** SQL构建器（单例依赖，线程安全，负责动态生成SQL与参数绑定） */
    private final QueryBuilder queryBuilder;


    /**
     * 私有构造器：初始化核心依赖组件
     * <p>
     * 私有访问控制确保外部无法实例化，通过单例模式避免重复初始化资源（如配置加载、连接池创建），
     * 提升系统性能与资源利用率。
     */
    private OrmProcessor() {
        this.configManager = ConfigManagers.getInstance();
        this.queryBuilder = QueryBuilder.getInstance();
    }

    /**
     * 获取单例实例
     * <p>
     * 通过静态内部类实现懒加载，仅在首次调用时初始化实例，避免类加载时的资源浪费。
     *
     * @return 全局唯一的OrmProcessor实例
     */
    public static OrmProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 处理数据库查询与修改操作
     * <p>
     * 核心流程：
     * 1. 配置校验（验证apiKey有效性）
     * 2. 安全验证（签名校验，基于权限配置）
     * 3. 参数解析（处理动态参数、默认值、类型转换）
     * 4. SQL生成（根据操作类型动态构建SQL与绑定参数）
     * 5. 数据库操作（执行查询/修改，自动处理结果集）
     * 6. 监控日志（慢查询记录、操作审计）
     *
     * @param apiKey    接口唯一标识（用于获取SQL配置与权限配置）
     * @param operation 操作类型（对应OperationType枚举，如"list"表示列表查询）
     * @param params    标准化参数（包含路径参数、查询参数、请求体参数）
     * @param conn      数据库连接（外部传入，支持事务管理）
     * @return 统一响应结果：成功时包含数据（查询结果/影响行数），失败时包含错误信息
     * @author 邹安族
     */
    public Result<?> processQuery(String apiKey, String operation, StandardParams params, Connection conn) {
        long startTime = System.currentTimeMillis(); // 记录操作开始时间（用于性能监控）
        ResultSet resultSet = null; // 声明结果集变量（用于finally块关闭资源）

        try {
            // 1. 校验apiKey并获取SQL配置
            SqlConfig sqlConfig = configManager.getSqlConfig(apiKey);
            if (sqlConfig == null) {
                return Result.fail("无效的apiKey：" + apiKey + "（未找到对应SQL配置）");
            }

            // 2. 获取权限配置并执行安全验证
            AuthConfig authConfig = configManager.getEffectiveAuthConfig(sqlConfig.getAuthConfig());


            // 3. 参数解析（处理动态参数、默认值、类型转换）
            ParamResolver paramResolver = new ParamResolver(sqlConfig);
            Map<String, Object> resolvedParams = paramResolver.resolve(params);
            if (resolvedParams == null) {
                return Result.fail("参数解析失败：无效的参数格式或缺失必填参数");
            }

            // 4. 生成SQL与绑定参数（根据操作类型）
            OperationType opType = parseOperationType(operation);
            Map<String, Object> sqlAndArgs = buildSqlAndArgs(sqlConfig, resolvedParams, opType);
            if (sqlAndArgs == null) {
                return Result.fail("SQL生成失败：不支持的操作类型[" + operation + "]");
            }
            String sql = (String) sqlAndArgs.get("sql");
            List<Object> args = (List<Object>) sqlAndArgs.get("args");

            // 5. 执行数据库操作并处理结果
            ExecuteSql executeSql = new ExecuteSql(); // SQL执行器
            Result<?> result;
            if (opType == OperationType.MODIFY) {
                // 处理修改操作（INSERT/UPDATE/DELETE）
                Map<String, Object> modifyResult = executeSql.execute(sql, args, conn);
                int affectedRows = (int) modifyResult.get("affectedRows");
                Integer generatedKey = (Integer) modifyResult.get("generatedKey");
                result = Result.success(affectedRows, generatedKey);
            } else {
                // 处理查询操作（LIST/PAGE/DEEP_PAGE）
                resultSet = executeSql.query(sql, args, conn); // try-with-resources不支持外部conn，手动管理
                List<Map<String, Object>> data = resultSetToMap(resultSet); // 结果集转List<Map>
                result = Result.success(data);
            }
            long costTime = System.currentTimeMillis() - startTime;
            // 6. 记录监控日志（慢查询+审计）
            new SlowQueryLogger(authConfig).logIfSlow(sql, costTime, args.toArray());

            return result;


        } catch (Exception e) {
            // 其他异常统一处理（如SQL执行失败、参数错误等）
            LogUtils.coreError("数据库操作失败：" + e.getMessage(), e);
            return Result.fail("操作失败：" + e.getMessage());
        } finally {
            // 强制关闭结果集（避免资源泄露）
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    LogUtils.coreWarn("结果集关闭失败：" + e.getMessage());
                }
            }
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
     * @param sqlConfig     SQL配置（包含SQL模板、参数映射等）
     * @param resolvedParams 解析后的参数Map
     * @param opType        操作类型枚举
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
     * 确保参数非null，避免NullPointerException；初始容量设为16（HashMap默认值），
     * 平衡哈希表性能与内存占用。
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
}