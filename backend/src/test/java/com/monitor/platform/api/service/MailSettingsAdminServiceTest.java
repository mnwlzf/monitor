package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.MailSettingsRequest;
import com.monitor.platform.api.dto.MailSettingsResponse;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.mail.MailService;
import com.monitor.platform.mail.MailSettingsEntity;
import com.monitor.platform.mail.MailSettingsMapper;
import com.monitor.platform.mail.MailSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 页面 SMTP 设置管理服务测试：密码加密存储、留空保留、默认值。
 */
class MailSettingsAdminServiceTest {

    private final FakeMailSettingsMapper mapper = new FakeMailSettingsMapper();
    private final MailSettingsRepository repository = new MailSettingsRepository(mapper);
    private final CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final MailSettingsAdminService service =
            new MailSettingsAdminService(repository, new MailService(), cipher);

    @Test
    void savesAndEncryptsPasswordWithoutReturningIt() {
        MailSettingsResponse response = service.save(new MailSettingsRequest(
                true, "smtp.qq.com", 465, "bot@qq.com", "s3cret", "bot@qq.com", "Monitor", true));

        assertThat(response.passwordConfigured()).isTrue();
        assertThat(response.host()).isEqualTo("smtp.qq.com");
        assertThat(response.port()).isEqualTo(465);
        assertThat(response.useTls()).isTrue();

        MailSettingsEntity stored = mapper.stored;
        assertThat(stored.getPasswordEncryptedPayload()).isNotBlank().isNotEqualTo("s3cret");
        assertThat(stored.getPasswordEncryptionAlgorithm()).isEqualTo(CredentialCipher.ALGORITHM);
        assertThat(cipher.decrypt(
                stored.getPasswordEncryptedPayload(),
                stored.getPasswordInitializationVector(),
                stored.getPasswordEncryptionAlgorithm(),
                stored.getPasswordKeyVersion())).isEqualTo("s3cret");
    }

    @Test
    void blankPasswordKeepsStoredCipher() {
        service.save(new MailSettingsRequest(
                true, "smtp.qq.com", 587, "bot@qq.com", "s3cret", null, "Monitor", false));
        String payload = mapper.stored.getPasswordEncryptedPayload();

        MailSettingsResponse response = service.save(new MailSettingsRequest(
                true, "smtp.example.com", 587, "bot@qq.com", "", null, "Monitor", false));

        assertThat(response.host()).isEqualTo("smtp.example.com");
        assertThat(response.passwordConfigured()).isTrue();
        assertThat(mapper.stored.getPasswordEncryptedPayload()).isEqualTo(payload);
    }

    @Test
    void enablingWithoutHostIsRejected() {
        assertThatThrownBy(() -> service.save(new MailSettingsRequest(
                true, "  ", 587, null, null, null, null, false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SMTP 主机");
    }

    @Test
    void getSettingsFallsBackToDefaults() {
        MailSettingsResponse response = service.getSettings();
        assertThat(response.enabled()).isFalse();
        assertThat(response.port()).isEqualTo(587);
        assertThat(response.passwordConfigured()).isFalse();
    }

    /** 内存版 Mapper，避免测试依赖数据库。 */
    private static class FakeMailSettingsMapper implements MailSettingsMapper {

        private MailSettingsEntity stored;

        @Override
        public MailSettingsEntity selectSettings() {
            return stored;
        }

        @Override
        public int upsertSettings(MailSettingsEntity entity) {
            stored = entity;
            return 1;
        }
    }
}