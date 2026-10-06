package com.monitor.platform.collector.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集范围拆分逻辑测试。
 */
class CollectionScopeTest {

    @Test
    void fullIncludesEverything() {
        assertThat(CollectionScope.FULL.includesBalance()).isTrue();
        assertThat(CollectionScope.FULL.includesGroups()).isTrue();
        assertThat(CollectionScope.FULL.includesApiKeys()).isTrue();
    }

    @Test
    void eachScopeOnlyIncludesItsOwnConcern() {
        assertThat(CollectionScope.BALANCE.includesBalance()).isTrue();
        assertThat(CollectionScope.BALANCE.includesGroups()).isFalse();
        assertThat(CollectionScope.BALANCE.includesApiKeys()).isFalse();

        assertThat(CollectionScope.GROUPS.includesBalance()).isFalse();
        assertThat(CollectionScope.GROUPS.includesGroups()).isTrue();
        assertThat(CollectionScope.GROUPS.includesApiKeys()).isFalse();

        assertThat(CollectionScope.API_KEYS.includesBalance()).isFalse();
        assertThat(CollectionScope.API_KEYS.includesGroups()).isFalse();
        assertThat(CollectionScope.API_KEYS.includesApiKeys()).isTrue();
    }
}