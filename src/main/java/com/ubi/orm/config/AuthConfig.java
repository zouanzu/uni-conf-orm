package com.ubi.orm.config;

import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class AuthConfig {
    private int signatureExpire = 300;
    private int rateLimitWindow =60;
    private int rateLimitMax = 100;
    private int intervalMin = 0;
    private String signatureAlgorithm = "sha256";
    private String auditFieldPrefix = "audit_";
    private String auditSignature = "signature";
    private String auditTimestamp = "timestamp";
    private String secretary;
    private boolean slowLog = true;
    private int slowLogThreshold = 1000;
    private String log = "info";
    private String logLevel = "info";
}