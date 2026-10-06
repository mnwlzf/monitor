package com.monitor.platform.collector.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.adapter.newapi.NewApiAdapter;
import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountApiKeySnapshotRepository;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.UpstreamGroupSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集启停与失败上报测试。
 *
 * <ul>
 *   <li>停用的平台/账号不参与采集；</li>
 *   <li>单个账号失败时，任务抛出带汇总与根因的错误，供页面展示。</li>
 * </ul>
 */
class CollectionFailureReportingTest {

    private PlatformRepository platformRepository;
    private AccountRepository accountRepository;
    private AccountCredentialService credentialService;
    private CollectionRunRepository collectionRunRepository;
    private NewApiAdapter newApiAdapter;
    private CollectionService service;

    private PlatformEntity platform;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        platformRepository = mock(PlatformRepository.class);
        accountRepository = mock(AccountRepository.class);
        credentialService = mock(AccountCredentialService.class);
        collectionRunRepository = mock(CollectionRunRepository.class);
        newApiAdapter = mock(NewApiAdapter.class);

        Executor synchronousExecutor = Runnable::run;
        service = new CollectionService(
                accountRepository,
                platformRepository,
                credentialService,
                collectionRunRepository,
                mock(AccountMetricSnapshotRepository.class),
                mock(AccountUsageDashboardSnapshotRepository.class),
                mock(UpstreamGroupRepository.class),
                mock(UpstreamGroupSnapshotRepository.class),
                mock(UpstreamChangeEventRepository.class),
                mock(AccountApiKeyRepository.class),
                mock(AccountApiKeySnapshotRepository.class),
                mock(CredentialCipher.class),
                newApiAdapter,
                mock(Sub2ApiAdapter.class),
                new ObjectMapper(),
                synchronousExecutor);

        platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("测试平台");
        platform.setPlatformType("newapi");
        platform.setUrl("https://upstream.example.com");
        platform.setStatus(true);

        account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        account.setUsername("tester");
        account.setEmail("tester@example.com");
        account.setStatus(true);

        when(platformRepository.findById(1)).thenReturn(Optional.of(platform));
        when(accountRepository.findById(7)).thenReturn(Optional.of(account));
        when(credentialService.resolvePassword(7)).thenReturn("pw");

        CollectionRunEntity run = new CollectionRunEntity();
        run.setId(99L);
        when(collectionRunRepository.start(eq(7), anyString(), anyString(), anyString())).thenReturn(run);
    }

    @Test
    void collectAllAccountsSurfacesPartialFailureWithRootCause() {
        when(platformRepository.findEnabled()).thenReturn(List.of(platform));
        when(accountRepository.findEnabledByPlatformId(1)).thenReturn(List.of(account));
        when(newApiAdapter.fetchSelf(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("上游返回 401 Unauthorized"));

        assertThatThrownBy(() -> service.collectAllAccounts(CollectionScope.BALANCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失败 1")
                .hasMessageContaining("上游返回 401 Unauthorized");
    }

    @Test
    void collectAccountRejectsDisabledAccount() {
        account.setStatus(false);

        assertThatThrownBy(() -> service.collectAccount(1, 7, CollectionScope.BALANCE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已停用");
    }

    @Test
    void collectAccountRejectsDisabledPlatform() {
        platform.setStatus(false);

        assertThatThrownBy(() -> service.collectAccount(1, 7, CollectionScope.BALANCE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台已停用");
    }
}