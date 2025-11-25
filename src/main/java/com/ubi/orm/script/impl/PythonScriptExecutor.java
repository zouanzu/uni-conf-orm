package com.ubi.orm.job.script.impl;

import com.ubi.orm.job.script.ScriptExecutor;
import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PythonScriptExecutor implements ScriptExecutor {
    // 缓存编译后的脚本（Python用字符串缓存，Jython不直接支持预编译）
    private final Map<String, String> scriptCache = new ConcurrentHashMap<>();

    @Override
    public Object execute(String scriptContent, Map<String, Object> context) {
        // 从缓存获取脚本（简单缓存，Jython预编译支持有限）
        String script = scriptCache.computeIfAbsent(scriptContent, k -> k);

        try (PythonInterpreter interpreter = new PythonInterpreter()) {
            // 绑定上下文参数（脚本中直接使用变量名）
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                interpreter.set(entry.getKey(), entry.getValue());
            }

            // 安全配置：禁止导入危险模块
            interpreter.exec("import sys\n" +
                    "def safe_import(name):\n" +
                    "    if name in ['os', 'subprocess', 'sys']:\n" +
                    "        raise ImportError('禁止导入危险模块: ' + name)\n" +
                    "    __import__(name)\n" +
                    "import __builtin__\n" +
                    "__builtin__.__import__ = safe_import");

            // 执行脚本并获取结果
            PyObject result = interpreter.eval(script);
            // 转换Python对象为Java对象
            return result.__tojava__(Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Python脚本执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getScriptType() {
        return "python";
    }
}
