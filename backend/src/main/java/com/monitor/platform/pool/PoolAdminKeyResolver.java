package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sub2API 管理员密钥解析器。
 *
 * <p>管理员密钥以 AES-GCM 密文形式保存在平台表，调用管理员接口前才解密，
 * 解密失败或未配置时返回空，由调用方给出明确提示。</p>
 */
@Component
public class PoolAdminKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(PoolAdminKeyResolver.class);

    private final CredentialCipher credentialCipher;

    public PoolAdminKeyResolver(CredentialCipher credentialCipher) {
        this.credentialCipher = credentialCipher;
    }

    /**
     * 解密平台的管理员密钥明文。
     */
    public Optional<String> resolve(PlatformEntity platform) {
        if (platform == null || !platform.hasAdminKey()) {
            return Optional.empty();
        }
        try {
            String plaintext = credentialCipher.decrypt(
                    platform.getAdminKeyEncryptedPayload(),
                    platform.getAdminKeyInitializationVector(),
                    platform.getAdminKeyEncryptionAlgorithm(),
                    platform.getAdminKeyKeyVersion());
            return Optional.ofNullable(plaintext).filter(value -> !value.isBlank());
        } catch (RuntimeException ex) {
            log.warn("解密平台管理员密钥失败: platformId={}, reason={}",
                    platform.getId(), ex.getMessage());
            return Optional.empty();
        }
    }
}