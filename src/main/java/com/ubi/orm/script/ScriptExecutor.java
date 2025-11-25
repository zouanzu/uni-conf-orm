package com.ubi.orm.job.script;

import java.util.Map;

/**
 * 脚本执行器接口：所有脚本类型都需实现此接口
 */
public interface ScriptExecutor {
    /**
     * 执行脚本
     * @param scriptContent 脚本内容
     * @param context 上下文参数（键值对）
     * @return 脚本执行结果
     */
    Object execute(String scriptContent, Map<String, Object> context);

    /**
     * 获取支持的脚本类型（如"groovy"、"js"、"python"）
     */
    String getScriptType();
}