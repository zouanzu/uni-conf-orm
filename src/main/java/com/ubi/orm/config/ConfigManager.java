package com.ubi.orm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
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

/**
 * 配置管理器（支持自定义目录加载配置，优化单例与路径处理）
 */
public class ConfigManager {
    // 单例实例（volatile确保多线程可见性）
    private static volatile ConfigManager INSTANCE;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();
    private final Map<String, SqlConfig> sqlConfigs = new ConcurrentHashMap<>();
    private final Map<String, JobConfig> jobConfigs = new ConcurrentHashMap<>();
    private DbConfig dbConfig;
    private AuthConfig globalAuthConfig;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClassLoader classLoader = ConfigManager.class.getClassLoader();
    private final String baseDir; // 基础目录（支持自定义）


    /**
     * 私有构造器：通过自定义基础目录初始化
     * @param baseDir 配置文件基础目录（相对路径基于此目录，为null时使用默认工作目录）
     */
    private ConfigManager(String baseDir) {
        // 确定基础目录：优先使用自定义目录，否则使用用户工作目录
        this.baseDir = baseDir;

        try {
            // 从环境变量/系统属性获取配置路径，默认值基于基础目录
            String dbPath = getConfigPathFromEnv("DB_CONFIG_PATH", "config/db-config.json");
            String sqlPattern = getConfigPathFromEnv("SQL_CONFIG_PATTERN", "config/sql-config*.json"); // 默认相对路径
            String jobPattern = getConfigPathFromEnv("JOB_CONFIG_PATTERN", "config/job-config*.json"); // 默认相对路径
            String authPath = getConfigPathFromEnv("AUTH_CONFIG_PATH", "config/auth-config.json");
            System.out.println("baseDir:"+this.baseDir);
            // 加载核心配置
            loadDbConfig(dbPath);
            incrementalLoadConfigs(
                    sqlPattern,
                    new TypeReference<Map<String, SqlConfig>>() {},
                    this::validateSqlConfig,
                    SqlConfig.class,
                    sqlConfigs,
                    "sql"
            );
            incrementalLoadConfigs(
                    jobPattern,
                    new TypeReference<Map<String, JobConfig>>() {},
                    this::validateJobConfig,
                    JobConfig.class,
                    jobConfigs,
                    "job"
            );
            loadGlobalAuthConfig(authPath);
        } catch (Exception e) {
            throw new RuntimeException("配置管理器初始化失败", e);
        }
    }


    /**
     * 获取单例实例（支持自定义基础目录）
     * @param baseDir 配置基础目录（可为null，使用默认值）
     * @return 全局唯一实例
     */
    public static ConfigManager getInstance(String baseDir) {
        String resolvedBaseDir = resolveBaseDirStatic(baseDir); // 提取静态方法解析baseDir
        if (INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManager(baseDir);
                }
            }
        } else {
            // 检查已存在实例的baseDir是否与新传入的一致
            if (!INSTANCE.baseDir.equals(resolvedBaseDir)) {
                throw new IllegalStateException("ConfigManager已初始化，baseDir不能更改（当前：" + INSTANCE.baseDir + "，新传入：" + resolvedBaseDir + "）");
            }
        }
        return INSTANCE;
    }

    // 提取静态方法用于解析baseDir（供getInstance校验使用）
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

    // 修改原resolveBaseDir为调用静态方法
    private String resolveBaseDir(String baseDir) {
        return resolveBaseDirStatic(baseDir);
    }

    /**
     * 便捷方法：使用默认基础目录获取实例
     */
    public static ConfigManager getInstance() {
        return getInstance(null);
    }





    // ========================= 通用配置加载（核心优化） =========================

    /**
     * 通用增量加载配置（泛型方法，支持各类配置）
     */
    private <T> void incrementalLoadConfigs(
            String patternPath,
            TypeReference<Map<String, T>> typeRef,
            BiValidator<String, T> validator,
            Class<T> clazz,
            Map<String, T> targetMap,
            String configType) throws Exception {
        writeLock.lock();
        try {
            Map<String, T> newConfigs = loadConfigMap(patternPath, typeRef,clazz);

            for (Map.Entry<String, T> entry : newConfigs.entrySet()) {
                String key = entry.getKey().trim();
                T newConfig = entry.getValue();
                if (validator.validate(key, newConfig)) {
                    targetMap.put(key, newConfig);
                }
            }
            notifyChange(configType);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 优化：支持单个条目解析失败时跳过，继续解析其他条目
     */
    private <T> Map<String, T> loadConfigMap(String patternPath, TypeReference<Map<String, T>> typeRef, Class<T> clazz) throws Exception {
        Map<String, T> configs = new HashMap<>();
        List<InputStream> streams = getResourcesByPattern(patternPath);

        for (InputStream is : streams) {
            // 先将整个JSON解析为Map<String, JsonNode>（原始JSON节点）
            Map<String, JsonNode> rawMap = objectMapper.readValue(is, new TypeReference<Map<String, JsonNode>>() {});

            // 逐个解析每个条目，捕获单个条目的异常
            for (Map.Entry<String, JsonNode> entry : rawMap.entrySet()) {
                String key = entry.getKey().trim();
                JsonNode valueNode = entry.getValue();
                try {
                    // 单独解析当前条目
                    T config = objectMapper.treeToValue(valueNode, clazz);
                    configs.put(key, config);
                } catch (MismatchedInputException e) {
                    // 记录错误，跳过当前条目
                    LogUtils.coreWarn("解析配置条目失败（key: " + key + "），已跳过。错误：" + e.getMessage());
                } catch (Exception e) {
                    // 处理其他可能的解析异常
                    LogUtils.coreWarn("解析配置条目异常（key: " + key + "），已跳过。错误：" + e.getMessage());
                }
            }
            is.close();
        }
        return configs;
    }

    /**
     * 函数式接口：配置验证器
     */
    @FunctionalInterface
    private interface BiValidator<K, V> {
        boolean validate(K key, V value);
    }


    // ========================= 对外暴露的加载方法 =========================

    public void incrementalLoadSqlConfigs(String patternPath) throws Exception {
        incrementalLoadConfigs(
                patternPath,
                new TypeReference<Map<String, SqlConfig>>() {},
                this::validateSqlConfig,
                SqlConfig.class,
                sqlConfigs,
                "sql"
        );
    }

    public void incrementalLoadJobConfigs(String patternPath) throws Exception {
        incrementalLoadConfigs(
                patternPath,
                new TypeReference<Map<String, JobConfig>>() {},
                this::validateJobConfig,
                JobConfig.class,
                jobConfigs,
                "job"
        );
    }


    // ========================= 配置更新与验证 =========================

    public void updateSqlConfig(String apiKey, SqlConfig newConfig) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("apiKey不能为空");
        }
        writeLock.lock();
        try {
            if (newConfig == null) {
                sqlConfigs.remove(apiKey.trim());
            } else if (validateSqlConfig(apiKey, newConfig)) {
                sqlConfigs.put(apiKey.trim(), newConfig);
            } else {
                throw new IllegalArgumentException("SQL配置验证失败");
            }
            notifyChange("sql");
        } finally {
            writeLock.unlock();
        }
    }

    public void updateJobConfig(String jobKey, JobConfig newConfig) {
        if (jobKey == null || jobKey.trim().isEmpty()) {
            throw new IllegalArgumentException("jobKey不能为空");
        }
        writeLock.lock();
        try {
            if (newConfig == null) {
                jobConfigs.remove(jobKey.trim());
            } else if (validateJobConfig(jobKey, newConfig)) {
                jobConfigs.put(jobKey.trim(), newConfig);
            } else {
                throw new IllegalArgumentException("Job配置验证失败");
            }
            notifyChange("job");
        } finally {
            writeLock.unlock();
        }
    }

    public void updateDbConfigField(String fieldName, Object value) throws Exception {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        writeLock.lock();
        try {
            if (dbConfig == null) {
                dbConfig = new DbConfig();
            }
            Field field = DbConfig.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(dbConfig, value);
            if (!validateDbConfig(dbConfig)) {
                throw new IllegalArgumentException("修改后DB配置不合法");
            }
            notifyChange("db");
        } finally {
            writeLock.unlock();
        }
    }

    private boolean validateSqlConfig(String apiKey, SqlConfig config) {
        return config != null && apiKey != null && !apiKey.isEmpty() ;
    }

    private boolean validateJobConfig(String jobKey, JobConfig config) {
        return config != null && jobKey != null && !jobKey.isEmpty();
    }

    private boolean validateDbConfig(DbConfig config) {
        return config != null && (config.getMysql() != null || config.getMssql() != null || config.getSqlite() != null);
    }


    // ========================= 基础配置加载 =========================

    private void loadDbConfig(String path) throws Exception {
        writeLock.lock();
        try {
            DbConfig newConfig = loadConfig(path, DbConfig.class);
            if (validateDbConfig(newConfig)) {
                this.dbConfig = newConfig;
                notifyChange("db");
            } else {
                throw new IllegalArgumentException("DB配置验证失败");
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void loadGlobalAuthConfig(String path) throws Exception {
        writeLock.lock();
        try {
            AuthConfig newConfig = loadConfig(path, AuthConfig.class);
            if (newConfig != null) {
                this.globalAuthConfig = newConfig;
                notifyChange("auth");
            }
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T loadConfig(String path, Class<T> clazz) throws IOException {
        try (InputStream is = getInputStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("配置文件不存在：" + path);
            }
            return objectMapper.readValue(is, clazz);
        }
    }


    // ========================= 资源加载核心逻辑（支持自定义目录） =========================

    /**
     * 按模式匹配加载资源（支持classpath/绝对路径/相对路径）
     */
    private List<InputStream> getResourcesByPattern(String patternPath) throws IOException, URISyntaxException {
        List<InputStream> result = new ArrayList<>();
        PathInfo pathInfo = parsePath(patternPath);

        switch (pathInfo.type) {
            case "classpath":
                result.addAll(loadClasspathResourcesByPattern(pathInfo.dirPath, pathInfo.fileNamePattern));
                break;
            case "absolute":
                result.addAll(loadFileResourcesByPattern(new File(pathInfo.dirPath), pathInfo.fileNamePattern));
                break;
            case "relative":
                // 相对路径基于自定义baseDir解析
                File absoluteDir = new File(baseDir, pathInfo.dirPath).getCanonicalFile();
                result.addAll(loadFileResourcesByPattern(absoluteDir, pathInfo.fileNamePattern));
                break;
            default:
                throw new IllegalArgumentException("不支持的路径类型：" + patternPath);
        }
        return result;
    }

    /**
     * 获取单个资源的输入流
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
                // 相对路径基于自定义baseDir解析
                File relativeFile = new File(baseDir, pathInfo.fullPath).getCanonicalFile();
                return relativeFile.exists() ? Files.newInputStream(relativeFile.toPath()) : null;
            default:
                return null;
        }
    }

    /**
     * 解析路径信息（类型/是否通配符/目录/文件名）
     */
    private PathInfo parsePath(String path) {
        PathInfo info = new PathInfo();
        String processedPath = path.trim().replace("\\", "/");

        // 识别路径类型：classpath/绝对路径/相对路径
        if (processedPath.startsWith("classpath:")) {
            info.type = "classpath";
            info.fullPath = processedPath.substring("classpath:".length());
        } else if (processedPath.startsWith("/")
                || (processedPath.length() >= 2 && Character.isLetter(processedPath.charAt(0))
                && processedPath.charAt(1) == ':')) {
            info.type = "absolute";
            info.fullPath = processedPath;
        } else {
            info.type = "relative";
            info.fullPath = processedPath;
        }

        // 解析通配符和目录/文件名
        info.isPattern = info.fullPath.contains("*");
        int lastSlashIndex = info.fullPath.lastIndexOf('/');
        if (lastSlashIndex == -1) {
            info.dirPath = "";
            info.fileNamePattern = info.fullPath;
        } else {
            info.dirPath = info.fullPath.substring(0, lastSlashIndex + 1);
            info.fileNamePattern = info.fullPath.substring(lastSlashIndex + 1);
        }

        return info;
    }

    /**
     * 加载classpath中的资源（支持jar包内资源）
     */
    private List<InputStream> loadClasspathResourcesByPattern(String dirPath, String fileNamePattern)
            throws IOException, URISyntaxException {
        List<InputStream> result = new ArrayList<>();
        Enumeration<URL> dirUrls = classLoader.getResources(dirPath);
        while (dirUrls.hasMoreElements()) {
            URL dirUrl = dirUrls.nextElement();
            switch (dirUrl.getProtocol()) {
                case "file":
                    File dir = new File(dirUrl.toURI());
                    result.addAll(loadFileResourcesByPattern(dir, fileNamePattern));
                    break;
                case "jar":
                    String jarPath = dirUrl.getPath().substring(5, dirUrl.getPath().indexOf('!'));
                    try (JarFile jar = new JarFile(jarPath)) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName().replace("\\", "/");
                            if (entryName.startsWith(dirPath) && !entry.isDirectory()) {
                                String fileName = entryName.substring(dirPath.length());
                                if (matchPattern(fileName, fileNamePattern)) {
                                    result.add(jar.getInputStream(entry));
                                }
                            }
                        }
                    }
                    break;
            }
        }
        return result;
    }

    /**
     * 加载文件系统中的资源
     */
    private List<InputStream> loadFileResourcesByPattern(File dir, String fileNamePattern) throws IOException {
        List<InputStream> result = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) {
            LogUtils.coreWarn("配置目录不存在：" + dir.getAbsolutePath()); // 打印实际目录
            return result;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && matchPattern(file.getName(), fileNamePattern)) {
                    LogUtils.coreInfo("加载配置文件：" + file.getAbsolutePath()); // 打印实际文件路径
                    result.add(new FileInputStream(file));
                }
            }
        }
        return result;
    }

    /**
     * 通配符匹配（*匹配任意字符）
     */
    private boolean matchPattern(String fileName, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return fileName.matches(regex);
    }

    /**
     * 从环境变量/系统属性获取配置路径（优先级：系统属性 > 环境变量 > 默认值）
     */
    private String getConfigPathFromEnv(String envKey, String defaultValue) {
        String path = System.getProperty(envKey); // 优先系统属性（-D参数）
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv(envKey); // 其次环境变量
        }
        return (path == null || path.trim().isEmpty()) ? defaultValue : path.trim();
    }


    // ========================= 配置变更通知 =========================

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
                System.err.println("配置变更通知失败：" + e.getMessage());
            }
        }
    }

    public interface ConfigChangeListener {
        void onConfigChanged(String configType);
    }


    // ========================= Getter方法 =========================

    public SqlConfig getSqlConfig(String apiKey) {
        readLock.lock();
        try {
            LogUtils.coreInfo(sqlConfigs.toString());
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