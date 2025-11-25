package com.ubi.orm.job;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务流执行结果类：记录任务整体状态和各步骤详情
 */
@Data
public class JobResult implements Serializable {
    // 任务是否执行成功
    private boolean success;
    // 任务执行消息（成功/失败描述）
    private String msg;
    // 任务总耗时（毫秒）
    private long totalTime;
    // 各步骤的执行结果
    private List<StepResult> steps = new ArrayList<>();

    // 私有构造（通过静态方法创建实例）
    private JobResult(boolean success, String msg, long totalTime, List<StepResult> steps) {
        this.success = success;
        this.msg = msg;
        this.totalTime = totalTime;
        this.steps = steps;
    }

    /**
     * 任务成功结果（带步骤详情）
     */
    public static JobResult success(List<StepResult> steps) {
        long totalTime = steps.stream().mapToLong(StepResult::getStepTime).sum();
        return new JobResult(true, "任务执行成功", totalTime, steps);
    }

    /**
     * 任务失败结果（带错误消息）
     */
    public static JobResult fail(String msg) {
        return new JobResult(false, msg, 0, new ArrayList<>());
    }

    /**
     * 任务步骤执行结果：记录单个步骤的详情
     */
    @Data
    public static class StepResult implements Serializable {
        // 步骤名称（如"api:user_insert"、"script:groovy"）
        private String stepName;
        // 步骤是否执行成功
        private boolean success;
        // 步骤执行耗时（毫秒）
        private long stepTime;
        // 步骤返回的数据（或错误信息）
        private Object data;

        // 空构造（方便动态设置字段）
        public StepResult() {}
    }
}
