package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集编排按平台类型分派给对应采集器。
 *
 * <p>拆出 {@link AccountCollector} 后，编排层只剩「选哪个采集器」，这里固定住这个契约：
 * 大小写不敏感地按 {@code platform_type} 命中，未知类型明确报错而不是静默跳过。</p>
 */
class CollectionServiceDispatchTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PlatformRepository platformRepository = mock(PlatformRepository.class);
    private final CollectionRunRepository collectionRunRepository = mock(CollectionRunRepository.class);
    private final AccountCollector newApiCollector = mock(AccountCollector.class);
    private final AccountCollector sub2Collector = mock(AccountCollector.class);

    private CollectionService service;
    private PlatformEntity platform;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        Executor synchronousExecutor = Runnable::run;
        when(newApiCollector.platformType()).thenReturn(CollectionPlatforms.NEW_API);
        when(sub2Collector.platformType()).thenReturn(CollectionPlatforms.SUB2_API);
        service = new CollectionService(
                accountRepository,
                platformRepository,
                collectionRunRepository,
                List.of(newApiCollector, sub2Collector),
                synchronousExecutor);

        platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("测试平台");
        platform.setUrl("https://upstream.example.com");
        platform.setStatus(true);

        account = new AccountEntity();
        account.setId(7);
        account.setPlatformId(1);
        account.setStatus(true);

        when(accountRepository.findById(7)).thenReturn(Optional.of(account));
        when(platformRepository.findById(1)).thenReturn(Optional.of(platform));

        CollectionRunEntity run = new CollectionRunEntity();
        run.setId(99L);
        when(collectionRunRepository.start(eq(7), anyString(), anyString(), anyString())).thenReturn(run);
    }

    @Test
    void routesSub2ApiPlatformToSub2Collector() {
        platform.setPlatformType("SUB2API");

        service.collectAccount(1, 7, CollectionScope.BALANCE);

        verify(sub2Collector).collect(eq(account), eq(platform), eq(99L), eq(CollectionScope.BALANCE));
        verify(newApiCollector, never()).collect(any(), any(), any(), any());
    }

    @Test
    void routesNewApiPlatformToNewApiCollector() {
        platform.setPlatformType("newapi");

        service.collectAccount(1, 7, CollectionScope.GROUPS);

        verify(newApiCollector).collect(eq(account), eq(platform), eq(99L), eq(CollectionScope.GROUPS));
        verify(sub2Collector, never()).collect(any(), any(), any(), any());
    }

    @Test
    void rejectsUnknownPlatformType() {
        platform.setPlatformType("unknown-platform");

        assertThatThrownBy(() -> service.collectAccount(1, 7, CollectionScope.BALANCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("账号采集失败");
        verify(newApiCollector, never()).collect(any(), any(), any(), any());
        verify(sub2Collector, never()).collect(any(), any(), any(), any());
    }
}
