package com.ubi.orm.config;

import lombok.Data;
import java.util.List;

@Data
public class JobConfig {
    private String jobKey;
    private boolean transaction = true;
    private List<JobStep> jobs;
    private AuthConfig authConfig; // 接口级安全配置
    private boolean requireAuth = false;//需要认证

    @Data
    public static class JobStep {
        private String type; // api/custom
        private String apiKey;//SqlConfig 中的apiKey
        private String operation;//list,page,modify
        private String scriptType;//js,groove
        private String scriptContent;// code-string

    }
}
