package com.monitor.platform.collector.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 采集任务处理器与采集范围的对应关系测试。
 */
class CollectionTaskHandlerTest {

    private final CollectionService collectionService = mock(CollectionService.class);

    @Test
    void handlersRouteToExpectedScope() {
        new CollectBalancesTaskHandler(collectionService).execute();
        verify(collectionService).collectAllAccounts(CollectionScope.BALANCE);

        new CollectGroupsTaskHandler(collectionService).execute();
        verify(collectionService).collectAllAccounts(CollectionScope.GROUPS);

        new CollectApiKeysTaskHandler(collectionService).execute();
        verify(collectionService).collectAllAccounts(CollectionScope.API_KEYS);

        new CollectAllAccountsTaskHandler(collectionService).execute();
        verify(collectionService).collectAllAccounts();
    }

    @Test
    void handlerCodesAreDistinctAndStable() {
        List<String> codes = List.of(
                new CollectBalancesTaskHandler(collectionService).code(),
                new CollectGroupsTaskHandler(collectionService).code(),
                new CollectApiKeysTaskHandler(collectionService).code(),
                new CollectAllAccountsTaskHandler(collectionService).code());

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).containsExactlyInAnyOrder(
                "collect-balances", "collect-groups", "collect-api-keys", "collect-all-accounts");
    }
}