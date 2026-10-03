package com.monitor.platform.collector.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 账号凭证加解密服务。
 *
 * <p>凭证使用 AES-GCM 加密，密文和初始化向量分开存储。密钥来自环境变量
 * {@code UPSTREAM_CREDENTIAL_KEY}，内容为 Base64 编码的 32 字节密钥。</p>
 */
@Service
public class CredentialCipher {

    public static final String ALGORITHM = "AES_GCM";
    public static final int CURRENT_KEY_VERSION = 1;

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    private final byte[] keyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 使用 Base64 编码的 32 字节密钥初始化加解密服务。
     *
     * @param base64Key 环境变量 UPSTREAM_CREDENTIAL_KEY
     */
    public CredentialCipher(@Value("${UPSTREAM_CREDENTIAL_KEY:}") String base64Key) {
        this.keyBytes = decodeKey(base64Key);
    }

    /**
     * 使用 AES-GCM 加密明文凭证，每次加密都会生成随机初始化向量。
     *
     * @param plaintext 明文密码
     * @return 可直接落库的密文和算法参数
     */
    public EncryptedCredential encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return new EncryptedCredential(
                    ALGORITHM,
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv),
                    CURRENT_KEY_VERSION
            );
        } catch (Exception ex) {
            throw new IllegalStateException("凭证加密失败", ex);
        }
    }

    /**
     * 校验算法与密钥版本后解密凭证。
     *
     * @return 明文密码
     */
    public String decrypt(String encryptedPayload, String initializationVector,
                          String algorithm, Integer keyVersion) {
        requireKey();
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalStateException("不支持的凭证加密算法: " + algorithm);
        }
        if (keyVersion == null || keyVersion != CURRENT_KEY_VERSION) {
            throw new IllegalStateException("不支持的凭证密钥版本: " + keyVersion);
        }

        try {
            byte[] iv = Base64.getDecoder().decode(initializationVector);
            byte[] encrypted = Base64.getDecoder().decode(encryptedPayload);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("凭证解密失败", ex);
        }
    }

    /**
     * 解码并校验密钥长度；配置无效时返回 null，由调用方给出明确错误。
     */
    private byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length != 32) {
                log.warn("UPSTREAM_CREDENTIAL_KEY 不是 32 字节密钥，凭证加解密不可用");
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            log.warn("UPSTREAM_CREDENTIAL_KEY 不是有效 Base64，凭证加解密不可用");
            return null;
        }
    }

    /**
     * 确保凭证密钥已正确配置。
     */
    private void requireKey() {
        if (keyBytes == null) {
            throw new IllegalStateException("未配置 UPSTREAM_CREDENTIAL_KEY");
        }
    }

    /**
     * 加密后的凭证信息。
     */
    public record EncryptedCredential(
            String algorithm,
            String encryptedPayload,
            String initializationVector,
            int keyVersion
    ) {
    }
}