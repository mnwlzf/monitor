package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游渠道倍率变化 → 我平台分组影响评估。
 *
 * <p>只按<strong>分组倍率</strong>判断（账号倍率不参与），账号与分组是多对多，
 * 所以一个账号会展开成多行。</p>
 */
class RateChangeImpactServiceTest {

    private AccountApiKeyRepository apiKeyRepository;
    private PoolAccountRepository poolAccountRepository;
    private Sub2AccountGroupRateReader groupRateReader;
    private RateChangeImpactService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(AccountApiKeyRepository.class);
        poolAccountRepository = mock(PoolAccountRepository.class);
        groupRateReader = mock(Sub2AccountGroupRateReader.class);
        service = new RateChangeImpactService(apiKeyRepository, poolAccountRepository, groupRateReader);

        AccountApiKeyEntity key = new AccountApiKeyEntity();
        key.setId(100L);
        key.setAccountId(10);
        key.setGroupName("渠道A");
        when(apiKeyRepository.findByAccount(anyInt(), anyBoolean())).thenReturn(List.of(key));

        PoolAccountEntity pool = new PoolAccountEntity();
        pool.setId(1L);
        pool.setPlatformId(1);
        pool.setExternalAccountId(52064L);
        pool.setName("满血");
        pool.setBoundKeyId(100L);
        when(poolAccountRepository.findDistinctPlatformIds()).thenReturn(List.of(1));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(pool));

        when(groupRateReader.isAvailable()).thenReturn(true);
        when(groupRateReader.groupsByAccount(any())).thenReturn(Map.of(52064L, List.of(
                new Sub2AccountGroupRateReader.AccountGroup(52064L, 22L, "ClaudeCode MAX20", new BigDecimal("1.25")),
                new Sub2AccountGroupRateReader.AccountGroup(52064L, 32L, "pro", new BigDecimal("0.30")))));
    }

    private static UpstreamChangeEventEntity rateEvent(String before, String after) {
        return event("RATE_CHANGED", "渠道A", before, after);
    }

    private static UpstreamChangeEventEntity event(String changeType, String groupName, String before, String after) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setId(1L);
        event.setAccountId(10);
        event.setEntityType("GROUP");
        event.setEntityKey("grp-1");
        event.setGroupName(groupName);
        event.setChangeType(changeType);
        event.setOldValue(before);
        event.setNewValue(after);
        return event;
    }

    @Test
    void marksEveryGroupWhoseRatioIsBelowTheNewRatio() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "1.50"));

        assertThat(rows).hasSize(2);
        RateChangeImpactService.ImpactRow first = rows.get(0);
        assertThat(first.accountName()).isEqualTo("满血");
        assertThat(first.groupName()).isEqualTo("ClaudeCode MAX20");
        assertThat(first.groupRatio()).isEqualByComparingTo("1.25");
        assertThat(first.newRatio()).isEqualByComparingTo("1.50");
        assertThat(first.affected()).isTrue();
        assertThat(first.level()).isEqualTo("CRITICAL");
        assertThat(rows).allMatch(RateChangeImpactService.ImpactRow::affected);
        assertThat(RateChangeImpactService.overallLevel(rows)).isEqualTo("CRITICAL");
    }

    @Test
    void marksOnlyGroupsBelowTheNewRatio() {
        // 新倍率 0.50：高于 pro 的 0.30，低于 ClaudeCode 的 1.25
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "0.50"));

        assertThat(rows).hasSize(2);
        // 受影响的排前面
        assertThat(rows.get(0).groupName()).isEqualTo("pro");
        assertThat(rows.get(0).affected()).isTrue();
        assertThat(rows.get(1).groupName()).isEqualTo("ClaudeCode MAX20");
        assertThat(rows.get(1).affected()).isFalse();
    }

    @Test
    void keepsWarningWhenIncreaseStaysBelowEveryGroupRatio() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "0.20"));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> !row.affected());
        assertThat(rows).allMatch(row -> "WARNING".equals(row.level()));
        assertThat(RateChangeImpactService.overallLevel(rows)).isEqualTo("WARNING");
    }

    @Test
    void treatsPriceCutAsInfoOnly() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("1.00", "0.50"));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> !row.affected());
        assertThat(rows).allMatch(row -> "INFO".equals(row.level()));
    }

    @Test
    void ignoresNonRateChangeEvents() {
        assertThat(service.impactOf(event("GROUP_UPDATED", "渠道A", null, "渠道改名"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenReadOnlyDatabaseUnavailable() {
        when(groupRateReader.isAvailable()).thenReturn(false);

        assertThat(service.impactOf(rateEvent("0.10", "1.50"))).isEmpty();
    }
}