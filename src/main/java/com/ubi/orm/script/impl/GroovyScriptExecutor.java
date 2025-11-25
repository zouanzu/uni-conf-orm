package com.ubi.orm.script.impl;

import com.ubi.orm.script.ScriptExecutor;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Groovy脚本执行器（单例模式）
 * 功能：编译并执行Groovy脚本，支持脚本缓存和安全限制（禁止访问危险类）
 * 设计：全局唯一实例，避免重复初始化Groovy编译器和缓存，提升执行效率
 */
public class GroovyScriptExecutor implements ScriptExecutor {

    // 静态内部类实现单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final GroovyScriptExecutor INSTANCE = new GroovyScriptExecutor();
    }

    // 缓存编译后的脚本（键：脚本内容，值：编译后的脚本对象）
    // 单例模式下全局共享缓存，避免重复编译
    private final Map<String, Script> scriptCache = new ConcurrentHashMap<>();

    // Groovy编译器配置（全局唯一，包含安全限制）
    private final CompilerConfiguration compilerConfig;


    /**
     * 私有构造器：初始化编译器配置和安全限制
     * 禁止外部实例化，确保全局唯一实例
     */
    private GroovyScriptExecutor() {
        // 初始化编译器配置，指定安全脚本基类
        compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(SafeGroovyScript.class.getName());
    }


    /**
     * 获取单例实例
     * @return 全局唯一的GroovyScriptExecutor实例
     */
    public static GroovyScriptExecutor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 执行Groovy脚本
     * @param scriptContent 脚本内容
     * @param context 脚本执行上下文（键值对参数，脚本中可直接通过变量名访问）
     * @return 脚本执行结果
     * @throws RuntimeException 脚本编译或执行失败时抛出
     */
    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        try {
            // 从缓存获取编译后的脚本（无缓存则编译，ConcurrentHashMap确保线程安全）
            Script script = scriptCache.computeIfAbsent(
                    scriptContent,
                    k -> new GroovyShell(compilerConfig).parse(k) // 首次执行时编译脚本
            );

            // 绑定上下文参数（脚本中可直接使用变量名，如context中的"name"可在脚本中直接用name访问）
            Binding binding = new Binding(context);
            script.setBinding(binding);

            // 执行脚本并返回结果
            return script.run();

        } catch (Exception e) {
            throw new RuntimeException("Groovy脚本执行失败: " + e.getMessage(), e);
        }
    }


    /**
     * 获取支持的脚本类型
     * @return 脚本类型标识（"groovy"）
     */
    @Override
    public String getScriptType() {
        return "groovy";
    }


    /**
     * Groovy安全脚本基类：限制对危险类（如System、Runtime）的访问，防止恶意操作
     */
    public static abstract class SafeGroovyScript extends Script {
        @Override
        public Object getProperty(String property) {
            // 禁止访问系统级危险类，防止脚本执行删除文件、执行系统命令等危险操作
            if (property.equals("System") || property.equals("Runtime") || property.equals("File")) {
                throw new SecurityException("禁止访问危险类: " + property);
            }
            return super.getProperty(property);
        }
    }
}