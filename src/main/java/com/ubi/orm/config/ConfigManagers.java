package com.ubi.orm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ubi.orm.monitor.log.LogUtils;
import lombok.SneakyThrows;
import java.io.*;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * 配置管理器（支持JSON/YAML混合加载、子文件夹递归扫描、按前缀自动分类）
 * @author 邹安族
 */
public class ConfigManagers {
    // 单例实例（volatile确保多线程可见性）
    private static volatile ConfigManagers INSTANCE;

    // 配置文件格式映射（扩展名 → ObjectMapper）
    private static final Map<String, ObjectMapper> FORMAT_MAPPERS = new HashMap<>();
    static {
        // 初始化JSON解析器
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 初始化YAML解析器（需要引入jackson-dataformat-yaml依赖）
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        FORMAT_MAPPERS.put("json", jsonMapper);
        FORMAT_MAPPERS.put("yaml", yamlMapper);
        FORMAT_MAPPERS.put("yml", yamlMapper); // 支持yml扩展名
    }

    // 文件名前缀与配置类型的映射（核心分类逻辑）
    private final Map<String, ConfigTypeInfo<?>> prefixConfigMap;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final Map<String, SqlConfig> sqlConfigs = new ConcurrentHashMap<>();
    private final Map<String, JobConfig> jobConfigs = new ConcurrentHashMap<>();
    private DbConfig dbConfig;
    private AuthConfig globalAuthConfig;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final ClassLoader classLoader = ConfigManagers.class.getClassLoader();
    private final String baseDir; // 基础目录（支持自定义）


    /**
     * 配置类型元信息（封装分类所需的所有信息）
     */
    private static class ConfigTypeInfo<T> {
        final Class<T> configClass; // 配置类类型
        final Map<String, T> targetMap; // 目标存储Map
        final BiValidator<String, T> validator; // 验证器
        final String configType; // 配置类型标识（用于通知）

        ConfigTypeInfo(Class<T> configClass, Map<String, T> targetMap, BiValidator<String, T> validator, String configType) {
            this.configClass = configClass;
            this.targetMap = targetMap;
            this.validator = validator;
            this.configType = configType;
        }
    }


    /**
     * 私有构造器：初始化前缀映射并加载配置
     */
    private ConfigManagers(String baseDir) {
        this.baseDir = resolveBaseDirStatic(baseDir);

        // 初始化前缀-配置类型映射（核心：按文件名前缀自动分类）
        this.prefixConfigMap = new HashMap<>();
        this.prefixConfigMap.put("sql-config", new ConfigTypeInfo<>(
                SqlConfig.class, sqlConfigs, this::validateSqlConfig, "sql"
        ));
        this.prefixConfigMap.put("job-config", new ConfigTypeInfo<>(
                JobConfig.class, jobConfigs, this::validateJobConfig, "job"
        ));

        try {
            // 从环境变量/系统属性获取配置路径模式，默认扫描config目录下所有子目录的JSON/YAML
            String mainPattern = getConfigPathFromEnv("CONFIG_PATTERN", "config/**/*");
            String dbPath = getConfigPathFromEnv("DB_CONFIG_PATH", "config/db-config");
            String authPath = getConfigPathFromEnv("AUTH_CONFIG_PATH", "config/auth-config");

            // 加载核心配置（DB和Auth）
            loadDbConfig(dbPath);
            loadGlobalAuthConfig(authPath);

            // 加载前缀匹配的配置（支持子文件夹和多格式）
            loadConfigsByPrefix(mainPattern);

        } catch (Exception e) {
            throw new RuntimeException("配置管理器初始化失败", e);
        }
    }


    // ========================= 单例与基础目录处理 =========================

    public static ConfigManagers getInstance(String baseDir) {
        String resolvedBaseDir = resolveBaseDirStatic(baseDir);
        if (INSTANCE == null) {
            synchronized (ConfigManagers.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManagers(baseDir);
                }
            }
        } else {
            if (!INSTANCE.baseDir.equals(resolvedBaseDir)) {
                throw new IllegalStateException("ConfigManagers已初始化，baseDir不能更改（当前：" + INSTANCE.baseDir + "，新传入：" + resolvedBaseDir + "）");
            }
        }
        return INSTANCE;
    }

    public static ConfigManagers getInstance() {
        return getInstance(null);
    }

    private static String resolveBaseDirStatic(String baseDir) {
        if (baseDir == null || baseDir.trim().isEmpty()) {
            return System.getProperty("user.dir");
        }
        File dir = new File(baseDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), baseDir);
        }
        try {
            return dir.getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException("无效的基础目录：" + baseDir, e);
        }
    }


    // ========================= 核心：按前缀和格式加载配置 =========================

    /**
     * 加载所有符合前缀规则的配置（支持子文件夹、JSON/YAML）
     */
    private void loadConfigsByPrefix(String patternPath) throws Exception {
        writeLock.lock();
        try {
            // 获取所有符合条件的文件（包括子文件夹）
            List<File> configFiles = listAllConfigFiles(patternPath);

            for (File file : configFiles) {
                // 解析文件名获取前缀和格式
                String fileName = file.getName();
                String prefix = getConfigPrefix(fileName);
                String ext = getFileExtension(fileName);

                // 跳过无匹配前缀或不支持的格式
                if (prefix == null || !FORMAT_MAPPERS.containsKey(ext)) {
                    LogUtils.coreDebug("跳过不匹配的文件：" + file.getAbsolutePath());
                    continue;
                }

                // 根据前缀获取配置类型信息
                ConfigTypeInfo<?> typeInfo = prefixConfigMap.get(prefix);
                if (typeInfo == null) {
                    LogUtils.coreWarn("未识别的配置前缀：" + prefix + "（文件：" + fileName + "）");
                    continue;
                }

                // 加载并解析文件
                loadAndMergeConfig(file, typeInfo, ext);
            }

            // 通知所有配置类型变更
            prefixConfigMap.values().forEach(info -> notifyChange(info.configType));
        } catch(Exception e){
            e.printStackTrace();
        }finally {
            writeLock.unlock();
        }
    }

    /**
     * 递归列出所有符合条件的配置文件（支持**匹配子文件夹）
     */
    private List<File> listAllConfigFiles(String patternPath) throws IOException, URISyntaxException {
        List<File> result = new ArrayList<>();
        PathInfo pathInfo = parsePath(patternPath);

        // 解析基础目录（不含通配符）
        File baseDirFile;
        switch (pathInfo.type) {
            case "classpath":
                // 处理classpath资源（简化：仅处理文件系统中的classpath资源）
                URL url = classLoader.getResource(pathInfo.dirPath);
                if (url == null || !"file".equals(url.getProtocol())) {
                    LogUtils.coreWarn("classpath路径不存在或不支持：" + pathInfo.dirPath);
                    return result;
                }
                baseDirFile = new File(url.toURI());
                break;
            case "absolute":
                baseDirFile = new File(pathInfo.dirPath);
                break;
            case "relative":
                // 基于baseDir拼接有效目录路径（不含通配符）
                baseDirFile = new File(this.baseDir, pathInfo.dirPath).getCanonicalFile();
                break;
            default:
                throw new IllegalArgumentException("不支持的路径类型：" + patternPath);
        }

        // 检查基础目录是否有效
        if (!baseDirFile.exists() || !baseDirFile.isDirectory()) {
            LogUtils.coreWarn("基础目录不存在或不是目录：" + baseDirFile.getAbsolutePath());
            return result;
        }

        // 根据文件名模式递归扫描（处理**和*）
        result.addAll(listFilesRecursively(
                baseDirFile,
                pathInfo.fileNamePattern
        ));

        return result;
    }

    /**
     * 递归扫描目录及其子目录，匹配文件名模式
     */
    private List<File> listFilesRecursively(File dir, String fileNamePattern) {
        List<File> result = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) {
            return result;
        }

        // 处理**模式：是否需要递归所有子目录
        boolean recursive = fileNamePattern.startsWith("**");
        // 提取实际文件名匹配模式（去除**前缀）
        String filePattern = recursive
                ? fileNamePattern.substring(2).replaceFirst("^/", "") // 去除**后的/
                : fileNamePattern;

        // 转换文件名模式为正则
        String regex = patternToRegex(filePattern);
        Pattern pattern = Pattern.compile(regex);

        // 扫描当前目录的文件
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // 如果是**模式，递归子目录
                    if (recursive) {
                        result.addAll(listFilesRecursively(file, fileNamePattern));
                    }
                } else {
                    // 匹配文件名模式
                    if (pattern.matcher(file.getName()).matches()) {
                        result.add(file);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 从文件名提取配置前缀（如"sql-config-xxx.json" → "sql-config"）
     */
    private String getConfigPrefix(String fileName) {
        int extIndex = fileName.lastIndexOf('.');
        String nameWithoutExt = extIndex > 0 ? fileName.substring(0, extIndex) : fileName;

        // 匹配前缀映射中的键（最长匹配优先）
        return prefixConfigMap.keySet().stream()
                .filter(prefix -> nameWithoutExt.startsWith(prefix))
                .max(Comparator.comparingInt(String::length)) // 优先匹配最长前缀
                .orElse(null);
    }

    /**
     * 获取文件扩展名（无点号）
     */
    private String getFileExtension(String fileName) {
        int extIndex = fileName.lastIndexOf('.');
        return extIndex > 0 && extIndex < fileName.length() - 1
                ? fileName.substring(extIndex + 1).toLowerCase()
                : null;
    }

    /**
     * 加载单个配置文件并合并到目标Map
     */
    @SuppressWarnings("unchecked")
    private <T> void loadAndMergeConfig(File file, ConfigTypeInfo<T> typeInfo, String ext) throws Exception {
        ObjectMapper mapper = FORMAT_MAPPERS.get(ext);
        try (InputStream is = Files.newInputStream(file.toPath())) {
            // 关键修正：构造具体的Map类型（Key为String，Value为目标类T）
            TypeFactory typeFactory = mapper.getTypeFactory();
            MapType mapType = typeFactory.constructMapType(HashMap.class, String.class, typeInfo.configClass);

            // 解析为明确类型的Map，而非泛型Map
            Map<String, T> configMap = mapper.readValue(is, mapType);

            // 验证并合并到目标Map
            for (Map.Entry<String, T> entry : configMap.entrySet()) {
                String key = entry.getKey().trim();
                T config = entry.getValue();
                if (typeInfo.validator.validate(key, config)) {
                    typeInfo.targetMap.put(key, config);
                    LogUtils.coreInfo("加载配置成功：" + file.getName() + "（key: " + key + "）");
                } else {
                    LogUtils.coreWarn("配置验证失败，跳过：" + file.getName() + "（key: " + key + "）");
                }
            }
        } catch (MismatchedInputException e) {
            LogUtils.coreError("配置格式不匹配：" + file.getAbsolutePath() + "，错误：" + e.getMessage(),e);
        } catch (Exception e) {
            LogUtils.coreError("加载配置文件失败：" + file.getAbsolutePath() + "，错误：" + e.getMessage(),e);
        }
    }


    // ========================= 基础配置（DB/Auth）加载 =========================

    /**
     * 加载DB配置（自动识别JSON/YAML）
     */
    private void loadDbConfig(String basePath) throws Exception {
        // 尝试所有支持的格式
        for (String ext : FORMAT_MAPPERS.keySet()) {
            String fullPath = basePath + "." + ext;
            try {
                DbConfig newConfig = loadSingleConfig(fullPath, DbConfig.class);
                if (validateDbConfig(newConfig)) {
                    this.dbConfig = newConfig;
                    notifyChange("db");
                    LogUtils.coreInfo("加载DB配置成功：" + fullPath);
                    return;
                }
            } catch (FileNotFoundException e) {
                LogUtils.coreDebug("DB配置文件不存在（尝试格式：" + ext + "）：" + fullPath);
            }
        }
        throw new FileNotFoundException("未找到DB配置文件（尝试所有格式）：" + basePath + ".[json|yaml|yml]");
    }

    /**
     * 加载全局Auth配置（自动识别JSON/YAML）
     */
    private void loadGlobalAuthConfig(String basePath) throws Exception {
        for (String ext : FORMAT_MAPPERS.keySet()) {
            String fullPath = basePath + "." + ext;
            try {
                AuthConfig newConfig = loadSingleConfig(fullPath, AuthConfig.class);
                if (newConfig != null) {
                    this.globalAuthConfig = newConfig;
                    notifyChange("auth");
                    LogUtils.coreInfo("加载Auth配置成功：" + fullPath);
                    return;
                }
            } catch (FileNotFoundException e) {
                LogUtils.coreDebug("Auth配置文件不存在（尝试格式：" + ext + "）：" + fullPath);
            }
        }
        LogUtils.coreWarn("未找到Auth配置文件，使用默认配置：" + basePath + ".[json|yaml|yml]");
    }

    /**
     * 加载单个配置文件（支持多格式）
     */
    private <T> T loadSingleConfig(String path, Class<T> clazz) throws IOException {
        PathInfo pathInfo = parsePath(path);
        String ext = getFileExtension(pathInfo.fullPath);
        ObjectMapper mapper = FORMAT_MAPPERS.getOrDefault(ext, new ObjectMapper());

        try (InputStream is = getInputStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("配置文件不存在：" + path);
            }
            return mapper.readValue(is, clazz);
        }
    }

    /**
     * 函数式接口：配置验证器
     */
    @FunctionalInterface
    private interface BiValidator<K, V> {
        boolean validate(K key, V value);
    }

    // ========================= 工具方法 =========================

    /**
     * 通配符转正则（支持**匹配子目录，*匹配文件名）
     */
    private String patternToRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return ".*"; // 匹配所有文件
        }
        return pattern.replace(".", "\\.")
                .replace("*", "[^/]*") // *匹配非/的任意字符
                .replace("?", ".")      // 补充?的支持（单个字符）
                + "$";
    }

    /**
     * 解析路径信息（扩展支持**子目录匹配）
     */
    private PathInfo parsePath(String path) {
        PathInfo info = new PathInfo();
        String processedPath = path.trim().replace("\\", "/");

        // 识别路径类型（classpath/absolute/relative）
        if (processedPath.startsWith("classpath:")) {
            info.type = "classpath";
            processedPath = processedPath.substring("classpath:".length());
        } else if (processedPath.startsWith("/")
                || (processedPath.length() >= 2 && Character.isLetter(processedPath.charAt(0))
                && processedPath.charAt(1) == ':')) {
            info.type = "absolute";
        } else {
            info.type = "relative";
        }

        // 关键修正：分离目录路径和通配符模式，确保dirPath不含通配符
        String fullPath = processedPath;
        info.isPattern = fullPath.contains("*");

        // 处理包含**的情况：将**前的部分作为目录路径，**及之后作为模式
        int doubleStarIndex = fullPath.indexOf("**");
        if (doubleStarIndex != -1) {
            // 目录路径取**之前的部分（确保是有效目录）
            info.dirPath = fullPath.substring(0, doubleStarIndex);
            // 文件名模式取**及之后的部分
            info.fileNamePattern = fullPath.substring(doubleStarIndex);
        } else {
            // 普通通配符（*）：按最后一个/分割目录和文件名模式
            int lastSlashIndex = fullPath.lastIndexOf('/');
            if (lastSlashIndex == -1) {
                info.dirPath = "";
                info.fileNamePattern = fullPath;
            } else {
                info.dirPath = fullPath.substring(0, lastSlashIndex + 1);
                info.fileNamePattern = fullPath.substring(lastSlashIndex + 1);
            }
        }

        // 确保dirPath不以/结尾时补全（避免路径拼接错误）
        if (!info.dirPath.isEmpty() && !info.dirPath.endsWith("/")) {
            info.dirPath += "/";
        }

        info.fullPath = fullPath;
        return info;
    }

    /**
     * 获取输入流（复用原有逻辑）
     */
    private InputStream getInputStream(String path) throws IOException {
        PathInfo pathInfo = parsePath(path);
        if (pathInfo.isPattern) {
            throw new IllegalArgumentException("单文件加载不支持通配符：" + path);
        }

        switch (pathInfo.type) {
            case "classpath":
                return classLoader.getResourceAsStream(pathInfo.fullPath);
            case "absolute":
                File absoluteFile = new File(pathInfo.fullPath);
                return absoluteFile.exists() ? Files.newInputStream(absoluteFile.toPath()) : null;
            case "relative":
                File relativeFile = new File(baseDir, pathInfo.fullPath).getCanonicalFile();
                return relativeFile.exists() ? Files.newInputStream(relativeFile.toPath()) : null;
            default:
                return null;
        }
    }

    private String getConfigPathFromEnv(String envKey, String defaultValue) {
        String path = System.getProperty(envKey);
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv(envKey);
        }
        return (path == null || path.trim().isEmpty()) ? defaultValue : path.trim();
    }


    // ========================= 验证器与变更通知 =========================

    private boolean validateSqlConfig(String apiKey, SqlConfig config) {
        return config != null && apiKey != null && !apiKey.isEmpty();
    }

    private boolean validateJobConfig(String jobKey, JobConfig config) {
        return config != null && jobKey != null && !jobKey.isEmpty();
    }

    private boolean validateDbConfig(DbConfig config) {
        return config != null && (config.getMysql() != null || config.getMssql() != null || config.getSqlite() != null);
    }

    public void addConfigChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeConfigChangeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyChange(String configType) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(configType);
            } catch (Exception e) {
                LogUtils.coreError("配置变更通知失败（类型：" + configType + "）：" + e.getMessage(),e);
            }
        }
    }

    public interface ConfigChangeListener {
        void onConfigChanged(String configType);
    }

// ========================= 对外暴露的加载方法 =========================

    public void incrementalLoadConfigs(String patternPath) throws Exception {
        try{
            loadConfigsByPrefix(patternPath);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // ========================= Getter方法 =========================

    public SqlConfig getSqlConfig(String apiKey) {
        readLock.lock();
        try {
            return apiKey == null ? null : sqlConfigs.get(apiKey);
        } finally {
            readLock.unlock();
        }
    }

    public JobConfig getJobConfig(String jobKey) {
        readLock.lock();
        try {
            return jobKey == null ? null : jobConfigs.get(jobKey.trim());
        } finally {
            readLock.unlock();
        }
    }

    public DbConfig getDbConfig() {
        readLock.lock();
        try {
            return dbConfig;
        } finally {
            readLock.unlock();
        }
    }

    public AuthConfig getEffectiveAuthConfig(AuthConfig authConfig) {
        readLock.lock();
        try {
            if (authConfig != null) {
                AuthConfig merged = new AuthConfig();
                copyProperties(globalAuthConfig, merged);
                copyProperties(authConfig, merged);
                return merged;
            }
            return globalAuthConfig;
        } finally {
            readLock.unlock();
        }
    }

    @SneakyThrows
    private void copyProperties(Object source, Object target) {
        if (source == null || target == null) return;
        Class<?> clazz = source.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(source);
                if (value != null) {
                    field.set(target, value);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }


    /**
     * 路径信息内部类
     */
    private static class PathInfo {
        String type; // classpath/absolute/relative
        String fullPath; // 完整路径
        String dirPath; // 目录路径
        String fileNamePattern; // 文件名模式（可能含*）
        boolean isPattern; // 是否含通配符
    }
}
