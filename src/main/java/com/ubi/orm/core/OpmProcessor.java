package com.ubi.orm.core;

import com.ubi.orm.config.AuthConfig;
import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.SqlConfig;
import com.ubi.orm.driver.DatabaseDriver;
import com.ubi.orm.driver.DriverFactory;
import com.ubi.orm.monitor.AuditLogger;
import com.ubi.orm.monitor.SlowQueryLogger;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.security.RateLimiter;
import com.ubi.orm.security.SignatureValidator;
import com.ubi.orm.monitor.log.LogUtils;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class OpmProcessor {
    private final ConfigManager configManager;
    private final DriverFactory driverFactory;

    public OpmProcessor(ConfigManager configManager, DriverFactory driverFactory) {
        this.configManager = configManager;
        this.driverFactory = driverFactory;
    }

    public Result<?> processQuery(String apiKey, String operation, StandardParams params, String clientIp) {
        long start = System.currentTimeMillis();
        try {
            SqlConfig sqlConfig = configManager.getSqlConfig(apiKey);
            if (sqlConfig == null) return Result.fail("apiKey not found");
            AuthConfig auth = configManager.getEffectiveAuthConfig(sqlConfig);

            // 安全验证
            Map<String, Object> paramMap = mergeParams(params);
            if (auth.getSecretkey() != null) {
                new SignatureValidator(auth).validate(paramMap);
            }
            if (auth.getRateLimitMax() > 0 || auth.getIntervalMin() > 0) {
                new RateLimiter(auth).check(clientIp);
            }

            // 参数解析
            ParamResolver resolver = new ParamResolver(sqlConfig);
            Map<String, Object> resolved = resolver.resolve(params);

            // SQL生成与执行
            DatabaseDriver driver = driverFactory.getDriver(sqlConfig.getDbDrive().getDrive());
            QueryBuilder builder = new QueryBuilder(sqlConfig, driver);
            List<Object> sqlParams = new ArrayList<>();
            boolean isPage = "page".equals(operation);
            int pageSize = isPage ? (int) resolved.getOrDefault("page_size", 10) : 0;
            int currentPage = isPage ? (int) resolved.getOrDefault("current_page", 1) : 0;
            String sql = builder.buildQuerySql(resolved, isPage, pageSize, currentPage, sqlParams);

            // 执行查询
            ResultSet rs = driver.query(sql, sqlParams, sqlConfig.getDbDrive().getHost());
            List<Map<String, Object>> data = resultSetToMap(rs);
            long total = isPage ? countTotal(sqlConfig, resolved, driver) : data.size();

            // 监控日志
            long cost = System.currentTimeMillis() - start;
            new SlowQueryLogger(auth).logIfSlow(sql, cost, sqlParams.toArray());
            new AuditLogger(auth).log(apiKey, resolved, cost);

            return Result.success(data, total);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    public Result<?> processModify(String apiKey, StandardParams params, String clientIp) {
        long start = System.currentTimeMillis();
        try {
            SqlConfig sqlConfig = configManager.getSqlConfig(apiKey);
            if (sqlConfig == null) return Result.fail("apiKey not found");
            AuthConfig auth = configManager.getEffectiveAuthConfig(sqlConfig);

            // 安全验证
            Map<String, Object> paramMap = mergeParams(params);
            if (auth.getSecretkey() != null) {
                new SignatureValidator(auth).validate(paramMap);
            }

            // 参数解析
            ParamResolver resolver = new ParamResolver(sqlConfig);
            Map<String, Object> resolved = resolver.resolve(params);

            // SQL生成与执行
            DatabaseDriver driver = driverFactory.getDriver(sqlConfig.getDbDrive().getDrive());
            QueryBuilder builder = new QueryBuilder(sqlConfig, driver);
            List<Object> sqlParams = new ArrayList<>();
            boolean isInsert = resolved.get(sqlConfig.getPk()) == null;
            String sql = builder.buildModifySql(resolved, isInsert, sqlParams);

            // 执行操作
            int rows = driver.execute(sql, sqlParams, sqlConfig.getDbDrive().getHost());
            Map<String, Object> result = new HashMap<>();
            result.put("rows", rows);

            // 监控日志
            long cost = System.currentTimeMillis() - start;
            new SlowQueryLogger(auth).logIfSlow(sql, cost, sqlParams.toArray());
            new AuditLogger(auth).log(apiKey, resolved, cost);

            return Result.success(result);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    private Map<String, Object> mergeParams(StandardParams params) {
        Map<String, Object> merged = new HashMap<>();
        merged.putAll(params.getPathParams());
        merged.putAll(params.getQueryParams());
        merged.putAll(params.getBodyParams());
        return merged;
    }

    private long countTotal(SqlConfig config, Map<String, Object> params, DatabaseDriver driver) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM " + config.getTableName();
        List<Object> countParams = new ArrayList<>();
        String where = new QueryBuilder(config, driver).buildWhereClause(params, countParams);
        if (!where.isEmpty()) countSql += " WHERE " + where;

        ResultSet rs = driver.query(countSql, countParams, config.getDbDrive().getHost());
        rs.next();
        return rs.getLong(1);
    }

    private List<Map<String, Object>> resultSetToMap(ResultSet rs) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            list.add(row);
        }
        return list;
    }
}
