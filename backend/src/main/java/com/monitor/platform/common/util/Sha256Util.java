package com.monitor.platform.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 十六进制摘要工具。
 */
public final class Sha256Util {

    private Sha256Util() {
    }

    /**
     * 计算文本的 SHA-256 小写十六进制摘要。
     */
    public static String hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }
}