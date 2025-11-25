package com.ubi.orm.job.script.impl;

import com.ubi.orm.job.script.ScriptExecutor;

import javax.script.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class JavaScriptExecutor implements ScriptExecutor {
    // 脚本引擎（Nashorn）
    private final ScriptEngine engine;
    // 缓存编译后的脚本
    private final Map<String, CompiledScript> scriptCache = new ConcurrentHashMap<>();

    public JavaScriptExecutor() {
        // 初始化Nashorn引擎
        engine = new ScriptEngineManager().getEngineByName("nashorn");
        if (engine == null) {
            throw new RuntimeException("未找到Nashorn引擎，请使用JDK8及以上版本");
        }
        // 安全配置：禁用eval和危险函数
        ((ScriptEngine) engine).put("eval", (Runnable) () -> {
            throw new SecurityException("禁止使用eval函数");
        });
    }

    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        try {
            // 绑定上下文参数（脚本中通过global对象访问）
            Bindings bindings = engine.createBindings();
            bindings.putAll(context);
            bindings.put("global", bindings); // 暴露全局对象

            // 编译并缓存脚本
            CompiledScript compiledScript = scriptCache.computeIfAbsent(
                    scriptContent,
                    k -> {
                        try {
                            return ((Compilable) engine).compile(k);
                        } catch (ScriptException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );

            // 执行脚本
            return compiledScript.eval(bindings);
        } catch (ScriptException e) {
            throw new RuntimeException("JavaScript脚本执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getScriptType() {
        return "js";
    }
}