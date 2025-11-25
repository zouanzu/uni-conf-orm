package com.ubi.orm.job.script;

import java.util.HashMap;
import java.util.Map;
import com.ubi.orm.job.script.impl.GroovyScriptExecutor;
import com.ubi.orm.job.script.impl.JavaScriptExecutor;
import com.ubi.orm.job.script.impl.PythonScriptExecutor;

/**
 * 脚本执行器工厂：管理所有脚本执行器，根据类型分发执行
 */

public class ScriptExecutorFactory {
    private final Map<String, ScriptExecutor> executorMap = new HashMap<>();

    // 自动注入所有实现了ScriptExecutor的Bean

    public ScriptExecutorFactory() {
        executorMap.put("groovy", new GroovyScriptExecutor());
        executorMap.put("js", new JavaScriptExecutor());
        executorMap.put("python", new PythonScriptExecutor());
    }

    /**
     * 根据脚本类型获取执行器
     * @param scriptType 脚本类型（如"js"、"python"）
     * @return 对应的脚本执行器
     */
    public ScriptExecutor getExecutor(String scriptType) {
        ScriptExecutor executor = executorMap.get(scriptType.toLowerCase());
        if (executor == null) {
            throw new IllegalArgumentException("不支持的脚本类型: " + scriptType +
                    "，支持的类型：" + executorMap.keySet());
        }
        return executor;
    }
}