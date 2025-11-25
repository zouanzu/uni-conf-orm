package com.ubi.orm.job.script.impl;

import com.ubi.orm.job.script.ScriptExecutor;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class GroovyScriptExecutor implements ScriptExecutor {
    // 缓存编译后的脚本（key：脚本内容，value：编译后的脚本对象）
    private final Map<String, Script> scriptCache = new ConcurrentHashMap<>();
    // 安全配置：限制危险操作
    private final CompilerConfiguration compilerConfig;

    public GroovyScriptExecutor() {
        compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(SafeGroovyScript.class.getName()); // 安全基类
    }

    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        try {
            // 从缓存获取编译后的脚本（无缓存则编译）
            Script script = scriptCache.computeIfAbsent(
                    scriptContent,
                    k -> new GroovyShell(compilerConfig).parse(k)
            );

            // 绑定上下文参数（脚本中可直接使用变量名访问）
            Binding binding = new Binding(context);
            script.setBinding(binding);

            // 执行脚本并返回结果
            return script.run();
        } catch (Exception e) {
            throw new RuntimeException("Groovy脚本执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getScriptType() {
        return "groovy";
    }

    /**
     * Groovy安全脚本基类：禁止危险操作
     */
    public static abstract class SafeGroovyScript extends Script {
        @Override
        public Object getProperty(String property) {
            // 禁止访问危险类
            if (property.equals("System") || property.equals("Runtime") || property.equals("File")) {
                throw new SecurityException("禁止访问危险类: " + property);
            }
            return super.getProperty(property);
        }
    }
}
