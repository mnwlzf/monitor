package com.monitor.platform.mail;

import com.monitor.platform.collector.security.CredentialCipher;
import org.springframework.stereotype.Service;

/**
 * SMTP 配置读取与解密。
 *
 * <p>供页面设置与余额提醒共用：从数据库读取 SMTP 设置并解密密码，组装成可发送的
 * {@link SmtpConfig}。</p>
 */
@Service
public class SmtpConfigProvider {

    private static final int DEFAULT_PORT = 587;

    private final MailSettingsRepository mailSettingsRepository;
    private final CredentialCipher credentialCipher;

    public SmtpConfigProvider(MailSettingsRepository mailSettingsRepository, CredentialCipher credentialCipher) {
        this.mailSettingsRepository = mailSettingsRepository;
        this.credentialCipher = credentialCipher;
    }

    /** 读取当前 SMTP 配置（密码已解密）。 */
    public SmtpConfig load() {
        return toConfig(mailSettingsRepository.get());
    }

    /** 把设置实体转换为发送配置（密码已解密）。 */
    public SmtpConfig toConfig(MailSettingsEntity entity) {
        String password = null;
        if (entity.hasPassword()) {
            password = credentialCipher.decrypt(
                    entity.getPasswordEncryptedPayload(),
                    entity.getPasswordInitializationVector(),
                    entity.getPasswordEncryptionAlgorithm(),
                    entity.getPasswordKeyVersion());
        }
        return new SmtpConfig(
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getHost(),
                entity.getPort() == null ? DEFAULT_PORT : entity.getPort(),
                entity.getUsername(),
                password,
                entity.getFromAddress(),
                entity.getFromName(),
                Boolean.TRUE.equals(entity.getUseTls()));
    }
}