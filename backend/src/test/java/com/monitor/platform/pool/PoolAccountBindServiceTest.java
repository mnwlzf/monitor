package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.common.util.Sha256Util;
import com.monitor.platform.pool.client.Sub2AdminAccountCredential;
import com.monitor.platform.pool.client.Sub2AdminClient;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号池账号自动绑定 / 手动绑定业务规则测试。
 */
@ExtendWith(MockitoExtension.class)
class PoolAccountBindServiceTest {

    @Mock private PoolAccountRepository poolAccountRepository;
    @Mock private AccountApiKeyRepository apiKeyRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private Sub2AdminClient adminClient;

    private PoolAccountBindService service;

    @BeforeEach
    void setUp() {
        service = new PoolAccountBindService(poolAccountRepository, apiKeyRepository, accountRepository, adminClient);
    }

    private PoolAccountEntity account(long externalId, String name, Long boundKeyId) {
        PoolAccountEntity entity = new PoolAccountEntity();
        entity.setPlatformId(1);
        entity.setExternalAccountId(externalId);
        entity.setName(name);
        entity.setBoundKeyId(boundKeyId);
        return entity;
    }

    @Test
    void shouldAutoBindBySha256Match() {
        String plaintext = "sk-abcdef123456";
        when(adminClient.fetchAccountCredentials("https://x", "admin"))
                .thenReturn(List.of(new Sub2AdminAccountCredential("满血", plaintext)));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L, "满血", null)));
        when(apiKeyRepository.findActiveKeyIdByHash(1, Sha256Util.hex(plaintext)))
                .thenReturn(Optional.of(9L));

        int bound = service.autoBind(1, "https://x", "admin");

        assertEquals(1, bound);
        verify(poolAccountRepository).updateBinding(1, 52064L, 9L);
    }

    @Test
    void shouldSkipWhenNoHashMatch() {
        when(adminClient.fetchAccountCredentials("https://x", "admin"))
                .thenReturn(List.of(new Sub2AdminAccountCredential("满血", "sk-unmatched")));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L, "满血", null)));
        when(apiKeyRepository.findActiveKeyIdByHash(1, Sha256Util.hex("sk-unmatched")))
                .thenReturn(Optional.empty());

        int bound = service.autoBind(1, "https://x", "admin");

        assertEquals(0, bound);
        verify(poolAccountRepository, never()).updateBinding(1, 52064L, null);
    }

    @Test
    void shouldNotRewriteBindingWhenAlreadyBound() {
        String plaintext = "sk-abcdef123456";
        when(adminClient.fetchAccountCredentials("https://x", "admin"))
                .thenReturn(List.of(new Sub2AdminAccountCredential("满血", plaintext)));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L, "满血", 9L)));
        when(apiKeyRepository.findActiveKeyIdByHash(1, Sha256Util.hex(plaintext)))
                .thenReturn(Optional.of(9L));

        int bound = service.autoBind(1, "https://x", "admin");

        assertEquals(1, bound);
        verify(poolAccountRepository, never()).updateBinding(1, 52064L, 9L);
    }

    @Test
    void shouldAllowManualBindToKeyFromAnotherPlatform() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 52064L))
                .thenReturn(Optional.of(account(52064L, "满血", null)));

        AccountApiKeyEntity key = new AccountApiKeyEntity();
        key.setId(9L);
        key.setAccountId(100);
        when(apiKeyRepository.findById(9L)).thenReturn(Optional.of(key));

        AccountEntity owner = new AccountEntity();
        owner.setId(100);
        owner.setPlatformId(2);
        when(accountRepository.findById(100)).thenReturn(Optional.of(owner));

        // 号池账号跨平台绑定是常态（自建 sub2api 的号池账号来自其它上游平台）
        service.bind(1, 52064L, 9L);

        verify(poolAccountRepository).updateBinding(1, 52064L, 9L);
    }

    @Test
    void shouldAllowManualUnbind() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 52064L))
                .thenReturn(Optional.of(account(52064L, "满血", 9L)));

        service.bind(1, 52064L, null);

        verify(poolAccountRepository).updateBinding(1, 52064L, null);
    }
}