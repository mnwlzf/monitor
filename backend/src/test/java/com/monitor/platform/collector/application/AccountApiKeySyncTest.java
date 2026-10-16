package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountApiKeySnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 密钥「使用中」的口径：我自己的平台在用它（号池账号绑定），不是上游把它标成 active。
 */
class AccountApiKeySyncTest {

    private final AccountApiKeyRepository apiKeyRepository = mock(AccountApiKeyRepository.class);
    private final AccountApiKeySnapshotRepository snapshotRepository = mock(AccountApiKeySnapshotRepository.class);
    private final UpstreamChangeRecorder changeRecorder = mock(UpstreamChangeRecorder.class);
    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);
    private final PoolAccountRepository poolAccountRepository = mock(PoolAccountRepository.class);

    private AccountApiKeySync sync;

    @BeforeEach
    void setUp() {
        sync = new AccountApiKeySync(apiKeyRepository, snapshotRepository, changeRecorder,
                credentialCipher, poolAccountRepository);
    }

    @Test
    void marksChangeInUseWhenPoolAccountBoundTheKey() {
        AccountApiKeyEntity key = key(7L, "官key-不降智", "ACTIVE");
        when(poolAccountRepository.findBoundKeyIds(anyCollection())).thenReturn(Set.of(7L));

        Set<Long> inUseKeyIds = sync.inUseKeyIds(List.of(key));

        sync.recordApiKeyChanges(account(), "sub2api", 1L, key,
                "官key-不降智", "ACTIVE", "官Key-不降智-无6.1sol", inUseKeyIds);

        verify(changeRecorder).recordApiKeyChange(any(), anyString(), any(), any(), anyString(),
                eq("group"), any(), any(), anyString(), eq(true));
    }

    @Test
    void treatsActiveButUnboundKeyAsNotInUse() {
        // 上游把它标成 ACTIVE（活跃），但没有任何号池账号绑定 —— 不算「使用中」
        AccountApiKeyEntity key = key(9L, "没人用的key", "ACTIVE");
        when(poolAccountRepository.findBoundKeyIds(anyCollection())).thenReturn(Set.of());

        Set<Long> inUseKeyIds = sync.inUseKeyIds(List.of(key));

        sync.recordApiKeyChanges(account(), "sub2api", 1L, key,
                "没人用的key", "ACTIVE", "新分组", inUseKeyIds);

        verify(changeRecorder).recordApiKeyChange(any(), anyString(), any(), any(), anyString(),
                eq("group"), any(), any(), anyString(), eq(false));
        verify(changeRecorder, never()).recordApiKeyChange(any(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(), anyString(), eq(true));
    }

    @Test
    void doesNotQueryBindingsWhenThereIsNoKey() {
        sync.inUseKeyIds(List.of());

        verify(poolAccountRepository, never()).findBoundKeyIds(anyCollection());
    }

    private static AccountEntity account() {
        AccountEntity account = new AccountEntity();
        account.setId(10);
        return account;
    }

    private static AccountApiKeyEntity key(Long id, String name, String status) {
        AccountApiKeyEntity key = new AccountApiKeyEntity();
        key.setId(id);
        key.setAccountId(10);
        key.setExternalKeyId("342");
        key.setKeyName(name);
        key.setStatus(status);
        key.setGroupName("官Key-不降智");
        return key;
    }
}
