package com.ubi.orm.core;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.driver.*;
import com.ubi.orm.monitor.AuditLogger;
import com.ubi.orm.monitor.SlowQueryLogger;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.security.RateLimiter;
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
 * 核心处理器（单例模式）
 * 功能：统一处理查询和修改操作，协调参数解析、SQL生成、安全验证、日志监控等流程
 * 设计：单例模式确保全局唯一实例，避免重复初始化资源，提升处理效率
 */
public class OrmProcessor {

    // 静态内部类实现单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final OrmProcessor INSTANCE = new OrmProcessor();
    }

    // 配置管理器（单例依赖）
    private final ConfigManager configManager;

    // 驱动工厂（单例依赖）
    private final ConnectionFactory connectionFactory;

    // SQL构建器（单例依赖）
    private final QueryBuilder queryBuilder;


    /**
     * 私有构造器：初始化核心依赖组件
     * 禁止外部实例化，确保单例唯一性
     */
    private OrmProcessor() {
        this.configManager = ConfigManager.getInstance();
        this.connectionFactory = ConnectionFactory.getInstance();
        this.queryBuilder = QueryBuilder.getInstance();
    }

    /**
     * 获取单例实例
     * @return 全局唯一的OpmProcessor实例
     */
    public static OrmProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 处理查询操作
     * 流程：安全验证 → 参数解析 → SQL生成 → 执行查询 → 结果转换 → 日志监控
     * @param apiKey 接口密钥（用于获取配置）
     * @param operation 操作标识（预留扩展用）
     * @param params 标准化参数（包含路径参数、查询参数、体参数）
     * @param clientFingerprint 客户端指纹（用于限流和审计）
     * @return 统一响应结果（成功包含数据，失败包含错误信息）
     */
    public Result<?> processQuery(String apiKey, String operation, StandardParams params, String clientFingerprint) {
        long startTime = System.currentTimeMillis(); // 记录开始时间（用于性能监控）
        Result<?> result = null;
        try {
            // 1. 获取SQL配置（校验apiKey有效性）
            SqlConfig sqlConfig = configManager.getSqlConfig(apiKey);
            if (sqlConfig == null) {
                return Result.fail("无效的apiKey：" + apiKey);
            }

            // 2. 获取有效的权限配置
            AuthConfig authConfig = configManager.getEffectiveAuthConfig(sqlConfig);

            // 3. 安全验证流程
            Map<String, Object> mergedParams = mergeParams(params); // 合并所有参数
            // 3.1 签名验证（如果配置要求）
            if (sqlConfig.isRequireAuth()) {
                new SignatureValidator(authConfig).validate(mergedParams);
            }
            // 3.2 限流检查（如果配置了限流规则）
            if (authConfig.getRateLimitMax() > 0 && authConfig.getRateLimitWindow() > 0) {
                RateLimiter.getInstance().check(
                        apiKey,
                        clientFingerprint,
                        authConfig.getRateLimitMax(),
                        authConfig.getRateLimitWindow(),
                        authConfig.getIntervalMin()
                );
            }

            // 4. 参数解析（处理动态参数、默认值等）
            ParamResolver paramResolver = new ParamResolver(sqlConfig);
            Map<String, Object> resolvedParams = paramResolver.resolve(params);


            // 5. 生成查询SQL和参数
            Map<String, Object> sqlAndArgs = build(sqlConfig, resolvedParams,operation);
            if (sqlAndArgs == null) {return Result.fail("无效的API调用！");}
            String querySql = (String) sqlAndArgs.get("sql");
            List<Object> queryArgs = (List<Object>) sqlAndArgs.get("args");
            // 6. 获取数据库驱动（基于配置的数据库类型）
            DatabaseConnection conns = connectionFactory.getConnection(sqlConfig.getDbDrive().getDrive());
            Connection conn=conns.getConnection(sqlConfig.getDbDrive().getHost());

            ExecuteSql executeSql=new ExecuteSql();
            // 7. 执行查询并转换结果
            if (operation.equals("modify")){
                Map<String,Object> res= executeSql.execute(querySql, queryArgs, conn);
                result=Result.success((int) res.get("affectedRows"),(Integer) res.get("generatedKey"));
            }else
            {
                ResultSet resultSet = executeSql.query(querySql, queryArgs, conn);
                List<Map<String, Object>> data = resultSetToMap(resultSet); // ResultSet转List<Map>
                result=Result.success(data);
            }


            // 9. 性能与审计日志
            long costTime = System.currentTimeMillis() - startTime;
            // 9.1 慢查询日志（超过阈值时记录）
            new SlowQueryLogger(authConfig).logIfSlow(querySql, costTime, queryArgs.toArray());
            // 9.2 审计日志（记录操作详情）
            new AuditLogger(authConfig).log(apiKey, resolvedParams, costTime);

            return result;

        } catch (Exception e) {
            // 异常统一处理：返回错误信息（生产环境可在此处添加异常日志）
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    private Map<String,Object> build(SqlConfig sqlConfig,Map<String, Object> resolvedParams,String operation) {
        switch (operation){
            case "modify": return queryBuilder.buildModify(sqlConfig, resolvedParams);
            case "list":  return queryBuilder.buildQuery(sqlConfig, resolvedParams);
            case "page":  return queryBuilder.buildPage(sqlConfig, resolvedParams);
            case "deepPage": return queryBuilder.buildDeepPage(sqlConfig, resolvedParams);
            default: return null;
        }
    }



    /**
     * 合并多源参数（路径参数、查询参数、体参数）
     * @param params 标准化参数对象
     * @return 合并后的参数Map
     */
    private Map<String, Object> mergeParams(StandardParams params) {
        Map<String, Object> merged = new HashMap<>(16); // 初始化容量，减少扩容开销
        merged.putAll(params.getPathParams());    // 路径参数（如/rest/{id}中的id）
        merged.putAll(params.getQueryParams());   // 查询参数（如?name=test）
        merged.putAll(params.getBodyParams());    // 体参数（如POST的JSON体）
        return merged;
    }





    /**
     * 将ResultSet转换为List<Map>（便于JSON序列化）
     * @param rs 结果集
     * @return 转换后的列表（每条记录为一个Map，key=列名，value=值）
     * @throws SQLException 结果集处理异常
     */
    private List<Map<String, Object>> resultSetToMap(ResultSet rs) throws SQLException {
        List<Map<String, Object>> resultList = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData(); // 获取列元数据
        int columnCount = metaData.getColumnCount();   // 列数量

        // 遍历结果集
        while (rs.next()) {
            Map<String, Object> rowMap = new HashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                // 列名作为key，列值作为value
                rowMap.put(metaData.getColumnName(i), rs.getObject(i));
            }
            resultList.add(rowMap);
        }
        return resultList;
    }
}
