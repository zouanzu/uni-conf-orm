package com.ubi.orm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class ConfigManager {
    private final Map<String, SqlConfig> sqlConfigs = new ConcurrentHashMap<>();
    private final Map<String, JobConfig> jobConfigs = new ConcurrentHashMap<>();
    private DbConfig dbConfig;
    private AuthConfig globalAuthConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public ConfigManager() {
        try{
            loadDbConfig();
            loadSqlConfigs();
            loadJobConfigs();
            loadGlobalAuthConfig();
        }catch(Exception e){
            throw new RuntimeException("加载任务配置失败",e);
        }
    }

    private void loadDbConfig() throws IOException {
        dbConfig = loadConfig("classpath:config/db-config.json", DbConfig.class);
    }

    private void loadSqlConfigs() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:config/sql-config*.json");
        for (Resource res : resources) {
            Map<String, SqlConfig> configs = objectMapper.readValue(
                    res.getInputStream(), new TypeReference<Map<String, SqlConfig>>() {});
            sqlConfigs.putAll(configs);
        }
    }

    private void loadJobConfigs() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:config/job-config*.json");
        for (Resource res : resources) {
            Map<String, JobConfig> configs = objectMapper.readValue(
                    res.getInputStream(), new TypeReference<Map<String, JobConfig>>() {});
            jobConfigs.putAll(configs);
        }
    }

    private void loadGlobalAuthConfig() throws IOException {
        try {
            globalAuthConfig = loadConfig("classpath:config/auth-config.json", AuthConfig.class);
        } catch (Exception e) {
            globalAuthConfig = new AuthConfig(); // 默认配置
        }
    }

    private <T> T loadConfig(String path, Class<T> clazz) throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver().getResource(path);
        return objectMapper.readValue(resource.getInputStream(), clazz);
    }

    // 获取有效配置（接口级覆盖全局）
    public AuthConfig getEffectiveAuthConfig(SqlConfig sqlConfig) {
        if (sqlConfig != null && sqlConfig.getAuthConfig() != null) {
            AuthConfig merged = new AuthConfig();
            org.springframework.beans.BeanUtils.copyProperties(globalAuthConfig, merged);
            org.springframework.beans.BeanUtils.copyProperties(sqlConfig.getAuthConfig(), merged);
            return merged;
        }
        return globalAuthConfig;
    }

    // Getters
    public SqlConfig getSqlConfig(String apiKey) { return sqlConfigs.get(apiKey); }
    public JobConfig getJobConfig(String jobKey) { return jobConfigs.get(jobKey); }
    public DbConfig getDbConfig() { return dbConfig; }
}