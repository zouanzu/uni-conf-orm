package com.ubi.orm.config;

import lombok.Data;
import java.util.List;

@Data
public class JobConfig {
    private String jobKey;
    private boolean transaction = true;
    private List<JobStep> jobs;

    @Data
    public static class JobStep {
        private String type; // api/custom
        private String apiKey;
        private String operation;
        private String scriptType;
        private String scriptContent;

    }
}
