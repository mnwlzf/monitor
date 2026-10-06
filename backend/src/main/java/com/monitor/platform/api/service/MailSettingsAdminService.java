package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.MailSettingsRequest;
import com.monitor.platform.api.dto.MailSettingsResponse;
import com.monitor.platform.api.dto.SendTestMailRequest;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.mail.MailService;
import com.monitor.platform.mail.MailSettingsEntity;
import com.monitor.platform.mail.MailSettingsRepository;
import com.monitor.platform.mail.SmtpConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.OffsetDateTime;

/**
 * 页面 SMTP 邮件设置管理服务。
 *
 * <p>负责读取/保存 SMTP 设置、加解密密码，以及「测试连接」「发送测试邮件」两个
 * 自检动作。密码沿用上游凭证的 AES-GCM 加密存储，接口不回传明文。</p>
 */
@Service
public class MailSettingsAdminService {

    private static final int DEFAULT_PORT = 587;
    private static final String DEFAULT_FROM_NAME = "Monitor";

    private final MailSettingsRepository mailSettingsRepository;
    private final MailService mailService;
    private final CredentialCipher credentialCipher;

    public MailSettingsAdminService(MailSettingsRepository mailSettingsRepository,
                                    MailService mailService,
                                    CredentialCipher credentialCipher) {
        this.mailSettingsRepository = mailSettingsRepository;
        this.mailService = mailService;
        this.credentialCipher = credentialCipher;
    }

    /** 读取当前 SMTP 设置（不含密码明文）。 */
    public MailSettingsResponse getSettings() {
        return toResponse(mailSettingsRepository.get());
    }

    /** 保存 SMTP 设置；密码留空表示保留原值。 */
    @Transactional
    public MailSettingsResponse save(MailSettingsRequest request) {
        MailSettingsEntity entity = mailSettingsRepository.get();
        apply(entity, request, true);
        entity.setUpdatedAt(OffsetDateTime.now());
        mailSettingsRepository.save(entity);
        return toResponse(entity);
    }

    /** 测试 SMTP 连接：传入表单则用表单临时配置，否则用已保存配置。 */
    public void testConnection(MailSettingsRequest override) {
        mailService.testConnection(resolveConfig(override));
    }

    /** 发送测试邮件。 */
    public void sendTestEmail(SendTestMailRequest request) {
        SmtpConfig config = resolveConfig(request.settings());
        mailService.send(config, request.to(), "Monitor SMTP 测试邮件", buildTestBody(config));
    }

    /**
     * 解析用于发送/测试的配置：传入临时表单时以已保存值为基底做覆盖，
     * 这样未填密码也能复用已保存的密码。
     */
    private SmtpConfig resolveConfig(MailSettingsRequest override) {
        MailSettingsEntity stored = mailSettingsRepository.get();
        SmtpConfig config;
        if (override == null || trimToNull(override.host()) == null) {
            config = toConfig(stored);
        } else {
            MailSettingsEntity merged = copy(stored);
            apply(merged, override, true);
            config = toConfig(merged);
        }
        // 自检动作不受「启用」开关限制：只要填了主机就允许测试连接/发送测试邮件
        return new SmtpConfig(true, config.host(), config.port(), config.username(),
                config.password(), config.from(), config.fromName(), config.useTls());
    }

    /** 把请求写入实体；{@code persistPassword=true} 时处理密码加解密。 */
    private void apply(MailSettingsEntity entity, MailSettingsRequest request, boolean persistPassword) {
        boolean enabled = request.enabled() != null && request.enabled();
        String host = trimToNull(request.host());
        if (enabled && host == null) {
            throw BusinessException.of("启用邮件通知时必须填写 SMTP 主机");
        }
        entity.setEnabled(enabled);
        entity.setHost(host);
        entity.setPort(request.port() == null ? DEFAULT_PORT : request.port());
        entity.setUsername(trimToNull(request.username()));
        entity.setFromAddress(trimToNull(request.from()));
        entity.setFromName(defaultIfNull(trimToNull(request.fromName()), DEFAULT_FROM_NAME));
        entity.setUseTls(request.useTls() != null && request.useTls());

        if (persistPassword && request.password() != null && !request.password().isBlank()) {
            CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(request.password());
            entity.setPasswordEncryptionAlgorithm(encrypted.algorithm());
            entity.setPasswordEncryptedPayload(encrypted.encryptedPayload());
            entity.setPasswordInitializationVector(encrypted.initializationVector());
            entity.setPasswordKeyVersion(encrypted.keyVersion());
        }
        // 密码留空：保留实体上已有的密文（来自数据库），实现「留空以保留当前值」
    }

    private SmtpConfig toConfig(MailSettingsEntity entity) {
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

    private MailSettingsResponse toResponse(MailSettingsEntity entity) {
        return new MailSettingsResponse(
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getHost(),
                entity.getPort() == null ? DEFAULT_PORT : entity.getPort(),
                entity.getUsername(),
                entity.hasPassword(),
                entity.getFromAddress(),
                entity.getFromName(),
                Boolean.TRUE.equals(entity.getUseTls()),
                entity.getUpdatedAt());
    }

    private MailSettingsEntity copy(MailSettingsEntity source) {
        MailSettingsEntity target = new MailSettingsEntity();
        target.setId(source.getId());
        target.setEnabled(source.getEnabled());
        target.setHost(source.getHost());
        target.setPort(source.getPort());
        target.setUsername(source.getUsername());
        target.setPasswordEncryptionAlgorithm(source.getPasswordEncryptionAlgorithm());
        target.setPasswordEncryptedPayload(source.getPasswordEncryptedPayload());
        target.setPasswordInitializationVector(source.getPasswordInitializationVector());
        target.setPasswordKeyVersion(source.getPasswordKeyVersion());
        target.setFromAddress(source.getFromAddress());
        target.setFromName(source.getFromName());
        target.setUseTls(source.getUseTls());
        target.setUpdatedAt(source.getUpdatedAt());
        return target;
    }

    private String buildTestBody(SmtpConfig config) {
        String endpoint = HtmlUtils.htmlEscape(config.host()) + ":" + config.port();
        return "<p>这是一封来自 Monitor 的 SMTP 测试邮件。</p>"
                + "<p>如果你收到此邮件，说明邮件服务配置正确。</p>"
                + "<p>SMTP 服务器：" + endpoint + "</p>";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfNull(String value, String fallback) {
        return value == null ? fallback : value;
    }
}