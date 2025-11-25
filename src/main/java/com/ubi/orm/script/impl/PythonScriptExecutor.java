package com.ubi.orm.script.impl;

import com.ubi.orm.script.ScriptExecutor;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Python脚本执行器（单例模式）
 * 功能：基于Jython执行Python脚本，支持脚本缓存和安全限制（禁止导入危险模块）
 * 设计：全局唯一实例，避免重复初始化缓存，提升执行效率
 */
public class PythonScriptExecutor implements ScriptExecutor {

    // 静态内部类实现单例（懒加载+线程安全）
    private static class SingletonHolder {
        private static final PythonScriptExecutor INSTANCE = new PythonScriptExecutor();
    }

    // 脚本缓存：键=脚本内容，值=脚本内容（Jython预编译支持有限，此处缓存原始脚本避免重复处理）
    // 单例模式下全局共享缓存，减少重复计算
    private final Map<String, String> scriptCache = new ConcurrentHashMap<>();


    /**
     * 私有构造器：禁止外部实例化，确保全局唯一实例
     */
    private PythonScriptExecutor() {
        // 初始化逻辑（如必要的Jython配置）可在此处添加
    }


    /**
     * 获取单例实例
     * @return 全局唯一的PythonScriptExecutor实例
     */
    public static PythonScriptExecutor getInstance() {
        return SingletonHolder.INSTANCE;
    }


    /**
     * 执行Python脚本
     * @param scriptContent 脚本内容
     * @param context 脚本执行上下文（键值对参数，脚本中可直接通过变量名访问）
     * @return 脚本执行结果（转换为Java对象）
     * @throws RuntimeException 脚本执行失败时抛出
     */
    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        // 从缓存获取脚本（无缓存则存入，ConcurrentHashMap确保线程安全）
        String script = scriptCache.computeIfAbsent(scriptContent, k -> k);

        // PythonInterpreter非线程安全，每个执行请求创建独立实例（try-with-resources自动关闭）
        try (PythonInterpreter interpreter = new PythonInterpreter()) {
            // 1. 绑定上下文参数（脚本中可直接使用变量名，如context中的"num"可在脚本中直接用num访问）
            if (context != null && !context.isEmpty()) {
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    interpreter.set(entry.getKey(), entry.getValue());
                }
            }
            //context的键会被直接绑定为脚本的全局变量，可通过键名直接访问
            // 2. 安全配置：重写import方法，禁止导入危险模块（os、subprocess等）
            interpreter.exec(
                    "import sys\n" +
                            "def safe_import(name):\n" +
                            "    if name in ['os', 'subprocess', 'sys', 'fileinput', 'shutil']:\n" +
                            "        raise ImportError('禁止导入危险模块: ' + name)\n" +  // 注意：Java 8 不支持 f-string，用 + 拼接
                            "    return __import__(name)\n" +
                            "import __builtin__\n" +
                            "__builtin__.__import__ = safe_import"
            );

            // 3. 执行脚本并获取结果
            PyObject result = interpreter.eval(script);

            // 4. 将Python对象转换为Java对象（支持常见类型：字符串、数字、列表等）
            return result.__tojava__(Object.class);

        } catch (Exception e) {
            throw new RuntimeException("Python脚本执行失败: " + e.getMessage(), e);
        }
    }


    /**
     * 获取支持的脚本类型
     * @return 脚本类型标识（"python"）
     */
    @Override
    public String getScriptType() {
        return "python";
    }
}