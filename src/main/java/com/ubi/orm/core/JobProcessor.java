package com.ubi.orm.core;

import com.ubi.orm.config.*;
import com.ubi.orm.driver.ConnectionFactory;
import com.ubi.orm.driver.DatabaseConnection;
import com.ubi.orm.monitor.log.LogUtils;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.script.ScriptExecutor;
import com.ubi.orm.script.ScriptExecutorFactory;
import com.ubi.orm.security.RateLimitException;
import com.ubi.orm.security.RateLimiter;
import com.ubi.orm.security.SignatureException;
import com.ubi.orm.security.SignatureValidator;
import com.ubi.orm.transaction.TransactionStatus;
import com.ubi.orm.transaction.impl.JdbcTransactionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务流处理器（跨多数据源事务版）
 * <p>
 * 功能：负责按配置顺序执行任务步骤（API/脚本），支持跨多数据源事务管理，
 * 实现同一线程内同一数据源的连接复用，确保线程安全与资源高效利用。
 * 无任何框架依赖，手动管理事务与资源生命周期。
 *
 * @author 邹安族
 * @date 2025年11月19日
 */
public class JobProcessor {

    /**
     * 步骤类型枚举（替代硬编码字符串，避免拼写错误）
     */
    public enum StepType {
        API,   // 数据库操作步骤（依赖ORM处理器）
        SCRIPT // 脚本执行步骤（不涉及数据库）
    }

    /**
     * 线程内连接缓存：key为数据源唯一标识（如URL），value为数据库连接
     * <p>
     * 设计目的：同一任务中多个API步骤若访问同一数据源，复用连接减少创建开销，
     * 且确保线程隔离（每个线程的缓存独立，避免线程安全问题）
     */
    private final ThreadLocal<Map<String, Connection>> threadLocalConnCache = ThreadLocal.withInitial(HashMap::new);

    // 静态内部类实现单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final JobProcessor INSTANCE = new JobProcessor();
    }

    /** 配置管理器（单例依赖，线程安全） */
    private final ConfigManagers configManager;

    /** 连接工厂（单例依赖，线程安全） */
    private final ConnectionFactory connectionFactory;

    /** ORM处理器（单例依赖，线程安全） */
    private final OrmProcessor ormProcessor;

    /** 脚本执行器工厂（单例依赖，线程安全） */
    private final ScriptExecutorFactory scriptExecutorFactory;

    /**
     * 私有构造器：初始化核心依赖组件
     * <p>
     * 私有访问控制确保单例唯一性，依赖的组件均为单例且线程安全，
     * 避免外部实例化导致的资源竞争问题。
     */
    private JobProcessor() {
        this.configManager = ConfigManagers.getInstance();
        this.connectionFactory = ConnectionFactory.getInstance();
        this.ormProcessor = OrmProcessor.getInstance();
        this.scriptExecutorFactory = ScriptExecutorFactory.getInstance();
    }

    /**
     * 获取单例实例
     * <p>
     * 通过静态内部类实现懒加载，确保类加载时的线程安全（JVM类加载机制保证）。
     *
     * @return 全局唯一的JobProcessor实例
     */
    public static JobProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 执行任务流（支持跨多数据源事务与线程内连接复用）
     * <p>
     * 核心流程：
     * 1. 加载任务配置并校验合法性
     * 2. 执行安全验证（签名+限流）
     * 3. 按顺序执行步骤（API/脚本），复用同一数据源连接
     * 4. 统一提交/回滚所有数据源事务
     * 5. 清理资源（关闭连接+清空线程缓存）
     *
     * @param jobKey           任务唯一标识（用于加载任务配置）
     * @param params           任务参数（包含path/query/body参数）
     * @param clientFingerprint 客户端标识（用于限流和日志追踪，如IP地址）
     * @return 任务执行结果（包含各步骤详情与总耗时）
     * @author 邹安族
     */
    public JobResult executeJob(String jobKey, StandardParams params, String clientFingerprint) {
        // 存储：数据源连接 -> 事务上下文（事务管理器+事务状态），用于统一提交/回滚
        Map<Connection, TransactionContext> txContextMap = new HashMap<>(4);
        // 合并参数上下文（path/query/body参数统一存储，供步骤间共享数据）
        Map<String, Object> context = mergeParams(params);
        // 记录任务总耗时
        long totalStartTime = System.currentTimeMillis();

        try {
            // 1. 加载任务配置并校验基础合法性
            JobConfig jobConfig = configManager.getJobConfig(jobKey);
            if (jobConfig == null) {
                return JobResult.fail("任务配置不存在: " + jobKey);
            }
            List<JobConfig.JobStep> steps = jobConfig.getJobs();
            if (steps == null || steps.isEmpty()) {
                return JobResult.fail("任务步骤为空: " + jobKey);
            }

            // 2. 加载权限配置并执行安全验证
            AuthConfig authConfig = configManager.getEffectiveAuthConfig(jobConfig.getAuthConfig());
            // 2.1 签名验证（若任务要求）
            if (jobConfig.isRequireAuth()) {
                try {
                    new SignatureValidator(authConfig).validate(context);
                } catch (SignatureException e) {
                    return JobResult.fail("签名验证失败: " + e.getMessage());
                }
            }
            // 2.2 限流检查（若配置了限流规则）
            if (authConfig.getRateLimitMax() > 0 && authConfig.getRateLimitWindow() > 0) {
                try {
                    RateLimiter.getInstance().check(
                            jobKey,
                            clientFingerprint,
                            authConfig.getRateLimitMax(),
                            authConfig.getRateLimitWindow(),
                            authConfig.getIntervalMin()
                    );
                } catch (RateLimitException e) {
                    return JobResult.fail("触发限流: " + e.getMessage());
                }
            }

            // 3. 按顺序执行所有任务步骤
            List<JobResult.StepResult> stepResults = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                JobConfig.JobStep step = steps.get(i);
                JobResult.StepResult stepResult = new JobResult.StepResult();
                stepResult.setStepName("step_" + (i + 1) + "_" + step.getType());
                long stepStartTime = System.currentTimeMillis();

                try {
                    Object stepData;
                    // 根据步骤类型执行对应逻辑
                    StepType stepType = StepType.valueOf(step.getType().toUpperCase());
                    switch (stepType) {
                        case API:
                            // API步骤：执行数据库操作，复用同一数据源连接
                            stepData = executeApiStep(step, params, jobConfig, txContextMap);
                            break;
                        case SCRIPT:
                            // 脚本步骤：执行脚本逻辑（不涉及数据库）
                            stepData = executeScriptStep(step, context);
                            break;
                        default:
                            throw new RuntimeException("不支持的步骤类型: " + step.getType());
                    }

                    // 记录步骤成功结果，并将结果存入上下文供后续步骤使用
                    stepResult.setSuccess(true);
                    stepResult.setData(stepData);
                    context.put(stepResult.getStepName(), stepData);
                } catch (Exception e) {
                    // 步骤执行失败：记录错误信息并抛出异常（触发全局回滚）
                    stepResult.setSuccess(false);
                    stepResult.setData(e.getMessage());
                    throw new RuntimeException("步骤[" + stepResult.getStepName() + "]执行失败", e);
                } finally {
                    // 记录步骤耗时并添加到结果列表
                    stepResult.setStepTime(System.currentTimeMillis() - stepStartTime);
                    stepResults.add(stepResult);
                }
            }

            // 4. 所有步骤执行成功：统一提交所有数据源事务
            if (jobConfig.isTransaction()) {
                commitAllTransactions(txContextMap);
            }

            // 5. 返回任务成功结果
            JobResult result = JobResult.success(stepResults);
            result.setTotalTime(System.currentTimeMillis() - totalStartTime);
            return result;

        } catch (Exception e) {
            // 6. 任务执行失败：统一回滚所有数据源事务
            if (!txContextMap.isEmpty()) {
                rollbackAllTransactions(txContextMap);
            }
            LogUtils.coreError("任务[" + jobKey + "]执行失败", e);
            JobResult result = JobResult.fail("任务执行失败: " + e.getMessage());
            result.setTotalTime(System.currentTimeMillis() - totalStartTime);
            return result;
        } finally {
            // 7. 清理资源：关闭所有连接并清空线程缓存（必须执行，避免内存泄漏）
            closeAllConnections();
            threadLocalConnCache.remove();
        }
    }

    /**
     * 执行API步骤（数据库操作）
     * <p>
     * 核心逻辑：从线程缓存获取同一数据源连接（不存在则创建），
     * 若任务需要事务则初始化事务，并关联到事务上下文。
     *
     * @param step         步骤配置
     * @param params       任务参数
     * @param jobConfig    任务全局配置
     * @param txContextMap 事务上下文映射（用于统一提交/回滚）
     * @return API执行结果数据
     * @throws Exception 执行过程中的异常（如配置不存在、SQL执行失败等）
     * * @author 邹安族
     */
    private Object executeApiStep(JobConfig.JobStep step, StandardParams params,
                                  JobConfig jobConfig, Map<Connection, TransactionContext> txContextMap) throws Exception {
        // 加载API对应的SQL配置
        SqlConfig sqlConfig = configManager.getSqlConfig(step.getApiKey());
        if (sqlConfig == null) {
            throw new RuntimeException("API配置不存在: " + step.getApiKey());
        }

        // 获取数据源唯一标识（使用数据库URL作为key，确保同一数据源的连接可复用）
        String dataSourceKey = sqlConfig.getDbDrive().getHost();
        // 从线程缓存获取连接（同一线程内同一数据源复用连接）
        Connection conn = getConnectionFromCache(sqlConfig);

        // 若任务需要事务，且连接未关联事务，则初始化事务
        if (jobConfig.isTransaction() && !txContextMap.containsKey(conn)) {
            JdbcTransactionManager txManager = new JdbcTransactionManager(conn);
            TransactionStatus tx = txManager.begin();
            txContextMap.put(conn, new TransactionContext(txManager, tx));
            LogUtils.coreInfo("数据源[" + dataSourceKey + "]初始化事务成功");
        }

        // 执行ORM查询并返回结果
        Result<?> apiResult = ormProcessor.processQuery(
                step.getApiKey(),
                step.getOperation(),
                params,
                conn
        );
        if (!apiResult.isSuccess()) {
            throw new RuntimeException(apiResult.getMsg());
        }
        return apiResult.getData();
    }

    /**
     * 执行脚本步骤（非数据库操作）
     * <p>
     * 核心逻辑：通过脚本执行器工厂获取对应类型的执行器，
     * 传入脚本内容和上下文参数执行脚本。
     *
     * @param step    步骤配置
     * @param context 上下文参数（包含前置步骤结果和原始参数）
     * @return 脚本执行结果数据
     * @author 邹安族
     */
    private Object executeScriptStep(JobConfig.JobStep step, Map<String, Object> context) {
        ScriptExecutor executor = scriptExecutorFactory.getExecutor(step.getScriptType());
        return executor.execute(step.getScriptContent(), context);
    }

    /**
     * 从线程缓存获取连接（不存在则创建并缓存）
     * <p>
     * 线程安全说明：通过ThreadLocal确保每个线程的缓存独立，
     * 避免多线程共享连接导致的线程安全问题（Connection非线程安全）。
     *
     * @param sqlConfig     SQL配置（包含数据源驱动信息）
     * @return 数据库连接（线程内唯一且复用）
     * @throws SQLException 获取连接失败时抛出
     * @author 邹安族
     */
    private Connection getConnectionFromCache(SqlConfig sqlConfig) throws SQLException {
        Map<String, Connection> connCache = threadLocalConnCache.get();
        // 缓存中存在连接且未关闭，则直接复用
        String dataSourceKey = sqlConfig.getDbDrive().getDrive()+sqlConfig.getDbDrive().getHost();
        if (connCache.containsKey(dataSourceKey)) {
            Connection cachedConn = connCache.get(dataSourceKey);
            if (!cachedConn.isClosed()) {
                LogUtils.coreInfo("复用数据源[" + dataSourceKey + "]的连接");
                return cachedConn;
            }
        }
        // 缓存中无有效连接，创建新连接并放入缓存
        DatabaseConnection dbConn = connectionFactory.getConnection(sqlConfig.getDbDrive().getDrive());
        Connection newConn = dbConn.getConnection(sqlConfig.getDbDrive().getHost());
        connCache.put(dataSourceKey, newConn);
        LogUtils.coreInfo("创建数据源[" + dataSourceKey + "]的新连接并缓存");
        return newConn;
    }

    /**
     * 合并所有参数（path/query/body）到上下文映射
     * <p>
     * 确保参数不为null，避免NPE；初始容量设为16（HashMap默认值），平衡性能与内存。
     *
     * @param params 标准参数对象（包含path/query/body参数）
     * @return 合并后的参数上下文
     * @author 邹安族
     */
    private Map<String, Object> mergeParams(StandardParams params) {
        Map<String, Object> context = new HashMap<>(16);
        if (params.getPathParams() != null) {
            context.putAll(params.getPathParams());
        }
        if (params.getQueryParams() != null) {
            context.putAll(params.getQueryParams());
        }
        if (params.getBodyParams() != null) {
            context.putAll(params.getBodyParams());
        }
        return context;
    }

    /**
     * 提交所有数据源的事务
     * <p>
     * 若某一事务提交失败，会触发所有已提交事务的回滚（补偿机制），
     * 尽可能保证跨数据源事务的一致性（最终一致性）。
     *
     * @param txContextMap 事务上下文映射
     * @throws SQLException 事务提交失败时抛出
     * @author 邹安族
     */
    private void commitAllTransactions(Map<Connection, TransactionContext> txContextMap) throws SQLException {
        for (Map.Entry<Connection, TransactionContext> entry : txContextMap.entrySet()) {
            Connection conn = entry.getKey();
            TransactionContext txCtx = entry.getValue();
            try {
                txCtx.txManager.commit(txCtx.txStatus);
                LogUtils.coreInfo("数据源[" + conn.getMetaData().getURL() + "]事务提交成功");
            } catch (SQLException e) {
                // 提交失败：回滚所有已提交的事务（补偿）
                rollbackAllTransactions(txContextMap);
                throw new SQLException("数据源[" + conn.getMetaData().getURL() + "]事务提交失败，已触发全局回滚", e);
            }
        }
    }

    /**
     * 回滚所有数据源的事务
     * <p>
     * 即使部分事务回滚失败，仍会继续回滚其他事务，确保损失最小化。
     *
     * @param txContextMap 事务上下文映射
     * @author 邹安族
     */
    private void rollbackAllTransactions(Map<Connection, TransactionContext> txContextMap) {
        for (Map.Entry<Connection, TransactionContext> entry : txContextMap.entrySet()) {
            Connection conn = entry.getKey();
            TransactionContext txCtx = entry.getValue();
            try {
                if (txCtx.txStatus != null) {
                    txCtx.txManager.rollback(txCtx.txStatus);
                    LogUtils.coreInfo("数据源[" + conn.getMetaData().getURL() + "]事务回滚成功");
                }
            } catch (SQLException e) {
                LogUtils.coreError("数据源[" + conn + "]事务回滚失败", e);
            }
        }
    }

    /**
     * 关闭线程缓存中的所有数据库连接
     * <p>
     * 无论连接是否处于事务中，均强制关闭（避免资源泄露），
     * 关闭前检查连接状态，避免重复关闭导致的异常。
     * @author 邹安族
     */
    private void closeAllConnections() {
        Map<String, Connection> connCache = threadLocalConnCache.get();
        if (connCache == null || connCache.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Connection> entry : connCache.entrySet()) {
            String dataSourceKey = entry.getKey();
            Connection conn = entry.getValue();
            try {
                if (!conn.isClosed()) {
                    conn.close();
                    LogUtils.coreInfo("数据源[" + dataSourceKey + "]连接已关闭");
                }
            } catch (SQLException e) {
                LogUtils.coreError("数据源[" + dataSourceKey + "]连接关闭失败", e);
            }
        }
    }

    /**
     * 事务上下文封装类
     * <p>
     * 用于关联连接对应的事务管理器和事务状态，便于统一管理事务生命周期。
     * @author 邹安族
     */
    private static class TransactionContext {
        /** 事务管理器（负责事务的提交/回滚） */
        JdbcTransactionManager txManager;
        /** 事务状态（记录事务当前状态） */
        TransactionStatus txStatus;

        /**
         * 构造事务上下文
         *
         * @param txManager 事务管理器
         * @param txStatus  事务状态
         *                  @author 邹安族
         */
        TransactionContext(JdbcTransactionManager txManager, TransactionStatus txStatus) {
            this.txManager = txManager;
            this.txStatus = txStatus;
        }
    }
}