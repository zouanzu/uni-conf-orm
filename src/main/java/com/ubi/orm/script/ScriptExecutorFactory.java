package com.ubi.orm.script;

import com.ubi.orm.script.impl.GroovyScriptExecutor;
import com.ubi.orm.script.impl.JavaScriptExecutor;
import com.ubi.orm.script.impl.PythonScriptExecutor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 脚本执行器工厂（单例模式）
 * 功能：管理所有脚本执行器，根据脚本类型动态获取执行器，支持执行器懒加载
 */
public class ScriptExecutorFactory {

    // 静态内部类实现工厂单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final ScriptExecutorFactory INSTANCE = new ScriptExecutorFactory();
    }

    // 执行器缓存：键=脚本类型（如"js"），值=执行器实例（懒加载初始化）
    // 使用ConcurrentHashMap保证多线程环境下的安全初始化
    private final Map<String, ScriptExecutor> executorMap = new ConcurrentHashMap<>();


    /**
     * 私有构造器：禁止外部实例化，确保工厂全局唯一
     */
    private ScriptExecutorFactory() {
        // 空构造：不提前初始化任何执行器，完全依赖懒加载
    }


    /**
     * 获取工厂单例实例
     * @return 全局唯一的ScriptExecutorFactory实例
     */
    public static ScriptExecutorFactory getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 根据脚本类型获取执行器（懒加载：首次使用时才初始化对应执行器）
     * @param scriptType 脚本类型（如"js"、"groovy"、"python"）
     * @return 对应的脚本执行器实例
     * @throws IllegalArgumentException 不支持的脚本类型时抛出
     */
    public ScriptExecutor getExecutor(String scriptType) {
        if (scriptType == null || scriptType.trim().isEmpty()) {
            throw new IllegalArgumentException("脚本类型不能为空");
        }

        String type = scriptType.toLowerCase(); // 统一转为小写，避免类型匹配问题

        // 核心：使用ConcurrentHashMap的computeIfAbsent实现懒加载+线程安全
        // 仅在第一次获取该类型执行器时才初始化，后续直接从缓存获取
        ScriptExecutor executor = executorMap.computeIfAbsent(type, key -> {
            switch (key) {
                case "js":
                    // JavaScript执行器：使用其单例的getInstance()，延迟到首次使用时加载
                    return JavaScriptExecutor.getInstance();
                case "groovy":
                    // Groovy执行器：首次使用时初始化
                    return GroovyScriptExecutor.getInstance();
                case "python":
                    // Python执行器：首次使用时初始化
                    return PythonScriptExecutor.getInstance();
                default:
                    // 不支持的类型直接抛出异常
                    throw new IllegalArgumentException("不支持的脚本类型: " + key +
                            "，支持的类型：js、groovy、python");
            }
        });

        return executor;
    }
}