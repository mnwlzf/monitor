package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.AccountCredentialRepository;
import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import com.monitor.platform.collector.repository.mapper.AccountCredentialMapper;
import com.monitor.platform.collector.security.CredentialCipher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号凭证服务测试：密码与手动 Token 都加密落库、按类型读取。
 */
class AccountCredentialServiceTest {

    private final FakeCredentialMapper mapper = new FakeCredentialMapper();
    private final CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final AccountCredentialService service =
            new AccountCredentialService(new AccountCredentialRepository(mapper), cipher);

    @Test
    void savesAndResolvesTokensByType() {
        service.saveToken(7, AccountCredentialService.REFRESH_TOKEN, "refresh-value");
        service.saveToken(7, AccountCredentialService.ACCESS_TOKEN, "access-value");

        assertThat(service.resolveToken(7, AccountCredentialService.REFRESH_TOKEN)).contains("refresh-value");
        assertThat(service.resolveToken(7, AccountCredentialService.ACCESS_TOKEN)).contains("access-value");
        assertThat(service.hasCredential(7, AccountCredentialService.PASSWORD)).isFalse();
        assertThat(mapper.stored.get(AccountCredentialService.REFRESH_TOKEN).getEncryptedPayload())
                .isNotBlank()
                .isNotEqualTo("refresh-value");
    }

    @Test
    void resolveTokenReturnsEmptyWhenMissing() {
        assertThat(service.resolveToken(7, AccountCredentialService.REFRESH_TOKEN)).isEmpty();
        assertThat(service.hasCredential(7, AccountCredentialService.PASSWORD)).isFalse();
    }

    @Test
    void savingSameTypeKeepsOnlyLatestActiveCredential() {
        service.saveToken(7, AccountCredentialService.REFRESH_TOKEN, "first");
        service.saveToken(7, AccountCredentialService.REFRESH_TOKEN, "second");

        assertThat(service.resolveToken(7, AccountCredentialService.REFRESH_TOKEN)).contains("second");
    }

    /** 内存版 Mapper，避免测试依赖数据库。 */
    private static class FakeCredentialMapper implements AccountCredentialMapper {

        private final Map<String, AccountCredentialEntity> stored = new HashMap<>();
        private long sequence = 0;

        @Override
        public int insertCredential(AccountCredentialEntity entity) {
            entity.setId(++sequence);
            stored.put(entity.getCredentialType(), entity);
            return 1;
        }

        @Override
        public int updateCredential(AccountCredentialEntity entity) {
            stored.put(entity.getCredentialType(), entity);
            return 1;
        }

        @Override
        public AccountCredentialEntity selectActiveCredential(Integer accountId, String credentialType) {
            AccountCredentialEntity entity = stored.get(credentialType);
            if (entity == null || !Boolean.TRUE.equals(entity.getIsActive())) {
                return null;
            }
            return entity;
        }

        @Override
        public int deactivateActiveCredential(Integer accountId, String credentialType, OffsetDateTime updatedAt) {
            AccountCredentialEntity entity = stored.get(credentialType);
            if (entity == null) {
                return 0;
            }
            entity.setIsActive(false);
            return 1;
        }

        @Override
        public int deactivateAllActiveCredentials(Integer accountId, OffsetDateTime updatedAt) {
            stored.values().forEach(entity -> entity.setIsActive(false));
            return stored.size();
        }
    }
}