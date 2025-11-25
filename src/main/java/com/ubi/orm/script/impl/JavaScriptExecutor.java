package com.ubi.orm.script.impl;

import com.ubi.orm.script.ScriptExecutor;

import javax.script.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * JavaScript脚本执行器（单例模式）
 * 功能：基于Nashorn引擎执行JavaScript脚本，支持脚本缓存和安全限制
 * 设计：全局唯一实例，避免重复初始化脚本引擎，提升执行效率
 */
public class JavaScriptExecutor implements ScriptExecutor {

    // 静态内部类实现单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final JavaScriptExecutor INSTANCE = new JavaScriptExecutor();
    }

    // 脚本引擎（Nashorn，全局唯一）
    private final ScriptEngine engine;

    // 缓存编译后的脚本（键：脚本内容，值：编译后的脚本对象）
    private final Map<String, CompiledScript> scriptCache = new ConcurrentHashMap<>();


    /**
     * 私有构造器：初始化脚本引擎并配置安全限制
     * 禁止外部实例化，确保全局唯一实例
     */
    private JavaScriptExecutor() {
        // 初始化Nashorn脚本引擎（JDK8及以上内置）
        engine = new ScriptEngineManager().getEngineByName("nashorn");
        if (engine == null) {
            throw new RuntimeException("未找到Nashorn引擎，请使用JDK8及以上版本");
        }

        // 安全配置：禁用危险函数（如eval），防止脚本注入攻击
        // 向引擎绑定一个自定义的eval函数，调用时抛出异常
        engine.put("eval", (Runnable) () -> {
            throw new SecurityException("禁止使用eval函数，存在安全风险");
        });
    }


    /**
     * 获取单例实例
     * @return 全局唯一的JavaScriptExecutor实例
     */
    public static JavaScriptExecutor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 执行JavaScript脚本
     * @param scriptContent 脚本内容
     * @param context 脚本执行上下文（键值对参数，脚本中可通过global对象访问）
     * @return 脚本执行结果
     * @throws RuntimeException 脚本编译或执行失败时抛出
     * context会被绑定到global对象（全局对象），脚本中通过global.键名访问参数。
     */
    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        try {
            // 创建绑定对象：存储脚本执行所需的参数（线程安全，每个执行请求独立）
            Bindings bindings = engine.createBindings();
            if (context != null) {
                bindings.putAll(context); // 注入上下文参数
            }
            // 暴露全局对象，脚本中可通过global访问所有参数（如global.param1）
            bindings.put("global", bindings);

            // 编译脚本并缓存（ConcurrentHashMap确保线程安全，避免重复编译）
            CompiledScript compiledScript = scriptCache.computeIfAbsent(
                    scriptContent,
                    k -> {
                        // 首次执行时编译脚本，后续直接从缓存获取
                        try {
                            // 校验引擎是否支持编译（Nashorn引擎支持）
                            if (!(engine instanceof Compilable)) {
                                throw new RuntimeException("当前脚本引擎不支持编译功能");
                            }
                            return ((Compilable) engine).compile(k);
                        } catch (ScriptException e) {
                            throw new RuntimeException("脚本编译失败: " + e.getMessage(), e);
                        }
                    }
            );

            // 执行编译后的脚本（复用编译结果，提升执行效率）
            return compiledScript.eval(bindings);

        } catch (ScriptException e) {
            throw new RuntimeException("JavaScript脚本执行失败: " + e.getMessage(), e);
        }
    }


    /**
     * 获取支持的脚本类型
     * @return 脚本类型标识（"js"表示JavaScript）
     */
    @Override
    public String getScriptType() {
        return "js";
    }
}