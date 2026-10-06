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
 * 采集失败上报测试：单个账号失败时，任务应抛出带汇总与根因的错误，供页面展示。
 */
class CollectionFailureReportingTest {

    @Test
    void collectAllAccountsSurfacesPartialFailureWithRootCause() {
        PlatformRepository platformRepository = mock(PlatformRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AccountCredentialService credentialService = mock(AccountCredentialService.class);
        CollectionRunRepository collectionRunRepository = mock(CollectionRunRepository.class);
        NewApiAdapter newApiAdapter = mock(NewApiAdapter.class);

        Executor synchronousExecutor = Runnable::run;
        CollectionService service = new CollectionService(
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

        PlatformEntity platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformType("newapi");
        platform.setUrl("https://upstream.example.com");
        when(platformRepository.findEnabled()).thenReturn(List.of(platform));
        when(platformRepository.findById(1)).thenReturn(Optional.of(platform));

        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        account.setUsername("tester");
        account.setEmail("tester@example.com");
        when(accountRepository.findEnabledByPlatformId(1)).thenReturn(List.of(account));
        when(accountRepository.findById(7)).thenReturn(Optional.of(account));
        when(credentialService.resolvePassword(7)).thenReturn("pw");

        CollectionRunEntity run = new CollectionRunEntity();
        run.setId(99L);
        when(collectionRunRepository.start(eq(7), eq("newapi"), anyString(), anyString())).thenReturn(run);

        when(newApiAdapter.fetchSelf(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("上游返回 401 Unauthorized"));

        assertThatThrownBy(() -> service.collectAllAccounts(CollectionScope.BALANCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失败 1")
                .hasMessageContaining("上游返回 401 Unauthorized");
    }
}