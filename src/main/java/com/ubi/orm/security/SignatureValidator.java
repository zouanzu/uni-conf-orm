package com.ubi.orm.security;

import com.ubi.orm.config.AuthConfig;
import org.apache.commons.codec.digest.DigestUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// 签名验证器
public class SignatureValidator {
    private final AuthConfig authConfig;

    public SignatureValidator(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    public boolean validate(Map<String, Object> params) {
        String timestamp = (String) params.get(authConfig.getAuditTimestamp());
        String signature = (String) params.get(authConfig.getAuditSignature());
        if (timestamp == null || signature == null) {
            throw new SecurityException("Missing timestamp or signature");
        }

        long ts = Long.parseLong(timestamp);
        long now = System.currentTimeMillis() / 1000;
        if (now - ts > authConfig.getSignatureExpire()) {
            throw new SecurityException("Signature expired");
        }

        TreeMap<String, Object> sorted = new TreeMap<>(params);
        sorted.remove(authConfig.getAuditSignature());
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append("=").append(v).append("&"));
        if (sb.length() > 0) sb.setLength(sb.length() - 1);

        String computed = computeSignature(sb.toString());
        return computed.equals(signature);
    }

    private String computeSignature(String source) {
        String key = authConfig.getSecretkey() != null ? authConfig.getSecretkey() :
                String.valueOf(System.currentTimeMillis() / 1000).substring(0, 9);

        try {
            switch (authConfig.getSignatureAlgorithm().toLowerCase()) {
                case "sha256":
                    return DigestUtils.sha256Hex(source + key);
                case "hmacsha256":
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
                    return Base64.getEncoder().encodeToString(mac.doFinal(source.getBytes()));
                default:
                    throw new SecurityException("Unsupported algorithm");
            }
        } catch (Exception e) {
            throw new SecurityException("Signature compute failed", e);
        }
    }
}

