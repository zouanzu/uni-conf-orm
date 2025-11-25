package com.ubi.orm.security;

import com.ubi.orm.config.AuthConfig;
import org.apache.commons.codec.digest.DigestUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

// 签名验证器
public class SignatureValidator {
    private final AuthConfig authConfig;

    public SignatureValidator(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    public String validate(Map<String, Object> params) throws SignatureException {
        String timestamp = (String) params.get(authConfig.getAuditTimestamp());
        String signature = (String) params.get(authConfig.getAuditSignature());
        if (timestamp == null || signature == null) {
            throw new SignatureException("Missing timestamp or signature");
        }

        long ts = Long.parseLong(timestamp);
        long now = System.currentTimeMillis() / 1000;
        if (now - ts > authConfig.getSignatureExpire()) {
            throw new SignatureException("Signature expired");
        }

        TreeMap<String, Object> sorted = new TreeMap<>(params);
        sorted.remove(authConfig.getAuditSignature());
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (k.startsWith(authConfig.getAuditFieldPrefix())) { // 仅处理键以"audit"开头的条目
                sb.append(k).append("=").append(v).append("&");
            }
        });
//        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        sb.append("timestamp=").append(timestamp);
        String computed = computeSignature(sb.toString());
        if (!computed.equals(signature)) {throw new SignatureException("Signature verification failed");}
        return sb.toString();
    }

    private String computeSignature(String source) throws SignatureException {
        String nowStr = String.valueOf( System.currentTimeMillis() / 1000);
// 核心逻辑：判断secretary是否为空，为空则取nowStr前9位（不足9位则取全部）
        String key = (authConfig.getSecretary() == null || authConfig.getSecretary().trim().isEmpty())
                ? nowStr.substring(0, Math.min(9, nowStr.length()))  // 取前9位（防止长度不足）
                : authConfig.getSecretary();
        try {
            String algorithm = authConfig.getSignatureAlgorithm().toLowerCase();
            switch (algorithm) {
                // 普通哈希算法（无密钥）
                case "md5":
                    return DigestUtils.md5Hex(source+key);
                case "sha1":
                    return DigestUtils.sha1Hex(source+key);
                case "sha256":
                    return DigestUtils.sha256Hex(source+key);

                // HMAC哈希算法（带密钥）
                case "hmacmd5":
                    Mac hmacMd5 = Mac.getInstance("HmacMD5");
                    hmacMd5.init(new SecretKeySpec(key.getBytes(), "HmacMD5"));
                    return Base64.getEncoder().encodeToString(hmacMd5.doFinal(source.getBytes()));
                case "hmacsha1":
                    Mac hmacSha1 = Mac.getInstance("HmacSHA1");
                    hmacSha1.init(new SecretKeySpec(key.getBytes(), "HmacSHA1"));
                    return Base64.getEncoder().encodeToString(hmacSha1.doFinal(source.getBytes()));
                case "hmacsha256":
                    Mac hmacSha256 = Mac.getInstance("HmacSHA256");
                    hmacSha256.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
                    return Base64.getEncoder().encodeToString(hmacSha256.doFinal(source.getBytes()));

                default:
                    throw new SignatureException("Unsupported algorithm: " + algorithm);
            }
        } catch (Exception e) {
            throw new SignatureException("Signature compute failed", e);
        }
    }
}

