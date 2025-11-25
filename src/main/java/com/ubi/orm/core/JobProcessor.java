package com.ubi.orm.core;

import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.JobConfig;
import com.ubi.orm.core.OpmProcessor;
import com.ubi.orm.core.Result;
import com.ubi.orm.job.JobResult;
import com.ubi.orm.job.script.ScriptExecutor;
import com.ubi.orm.job.script.ScriptExecutorFactory;
import com.ubi.orm.param.StandardParams;
import com.ubi.orm.transaction.JdbcTransactionManager;
import com.ubi.orm.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯Java任务流处理器：无任何框架依赖，手动管理依赖和执行流程
 */
public class JobProcessor {
    // 核心依赖（通过构造函数手动传入，无框架注入）
    private final ConfigManager configManager;
    private final OpmProcessor opmProcessor;
    private final ScriptExecutorFactory scriptExecutorFactory;
    private final JdbcTransactionManager txManager = new JdbcTransactionManager(); // 手动实例化事务管理器

    /**
     * 构造函数：手动传入所有依赖（去框架化核心）
     */
    public JobProcessor(ConfigManager configManager, OpmProcessor opmProcessor, ScriptExecutorFactory scriptExecutorFactory) {
        this.configManager = configManager;
        this.opmProcessor = opmProcessor;
        this.scriptExecutorFactory = scriptExecutorFactory;
    }

    /**
     * 执行任务流
     * @param jobKey 任务唯一标识
     * @param params 任务参数
     * @param clientIp 客户端IP（用于日志等）
     * @return 任务执行结果
     */
    public JobResult executeJob(String jobKey, StandardParams params, String clientIp) {
        // 1. 加载任务配置
        JobConfig jobConfig = configManager.getJobConfig(jobKey);
        if (jobConfig == null) {
            return JobResult.fail("任务不存在: " + jobKey);
        }

        // 2. 初始化事务（如需事务）
        TransactionStatus tx = jobConfig.isTransaction() ? txManager.begin() : null;
        Map<String, Object> context = mergeParams(params); // 上下文：存储所有参数和步骤结果
        long totalStartTime = System.currentTimeMillis();

        try {
            List<JobResult.StepResult> steps = new ArrayList<>();

            // 3. 按顺序执行任务步骤
            for (int i = 0; i < jobConfig.getJobs().size(); i++) {
                JobConfig.JobStep step = jobConfig.getJobs().get(i);
                JobResult.StepResult stepResult = new JobResult.StepResult();
                stepResult.setStepName("step_" + (i + 1) + "_" + step.getType());
                long stepStartTime = System.currentTimeMillis();

                try {
                    Object stepData;
                    // 根据步骤类型执行（API/脚本）
                    switch (step.getType()) {
                        case "api":
                            // 执行API步骤（调用SQL处理器）
                            Result<?> apiResult = opmProcessor.processQuery(
                                    step.getApiKey(),
                                    step.getOperation(),
                                    params,
                                    clientIp
                            );
                            if (!apiResult.isSuccess()) {
                                throw new RuntimeException(apiResult.getMsg());
                            }
                            stepData = apiResult.getData();
                            break;

                        case "script":
                            // 执行脚本步骤（调用脚本执行器）
                            ScriptExecutor executor = scriptExecutorFactory.getExecutor(step.getScriptType());
                            stepData = executor.execute(step.getScriptContent(), context);
                            break;

                        default:
                            throw new RuntimeException("不支持的步骤类型: " + step.getType());
                    }

                    // 记录步骤成功结果
                    stepResult.setSuccess(true);
                    stepResult.setData(stepData);
                    context.put(stepResult.getStepName(), stepData); // 步骤结果存入上下文
                } catch (Exception e) {
                    // 记录步骤失败结果
                    stepResult.setSuccess(false);
                    stepResult.setData(e.getMessage());
                    throw e; // 抛出异常触发事务回滚
                } finally {
                    // 记录步骤耗时
                    stepResult.setStepTime(System.currentTimeMillis() - stepStartTime);
                    steps.add(stepResult);
                }
            }

            // 4. 所有步骤成功，提交事务
            if (tx != null) {
                txManager.commit(tx);
            }

            // 5. 返回任务成功结果
            JobResult result = JobResult.success(steps);
            result.setTotalTime(System.currentTimeMillis() - totalStartTime);
            return result;

        } catch (Exception e) {
            // 6. 执行失败，回滚事务
            if (tx != null) {
                txManager.rollback(tx);
            }
            JobResult result = JobResult.fail("任务执行失败: " + e.getMessage());
            result.setTotalTime(System.currentTimeMillis() - totalStartTime);
            return result;
        }
    }

    /**
     * 合并所有参数到上下文（path/query/body）
     */
    private Map<String, Object> mergeParams(StandardParams params) {
        Map<String, Object> context = new HashMap<>();
        context.putAll(params.getPathParams());
        context.putAll(params.getQueryParams());
        context.putAll(params.getBodyParams());
        return context;
    }
}