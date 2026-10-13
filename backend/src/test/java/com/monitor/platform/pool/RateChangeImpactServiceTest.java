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
 * <p>结算倍率 = 分组倍率 × 账号倍率（Sub2API 口径）；账号与分组是多对多，
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
        pool.setRateMultiplier(new BigDecimal("0.8"));
        pool.setBoundKeyId(100L);
        when(poolAccountRepository.findDistinctPlatformIds()).thenReturn(List.of(1));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(pool));

        when(groupRateReader.isAvailable()).thenReturn(true);
        when(groupRateReader.ratesByAccount(any())).thenReturn(Map.of(52064L, List.of(
                // 结算 1.25 × 0.8 = 1.00
                new Sub2AccountGroupRateReader.AccountGroupRate(52064L, new BigDecimal("0.8"), 22L,
                        "ClaudeCode MAX20", new BigDecimal("1.25")),
                // 结算 0.30 × 0.5 = 0.15
                new Sub2AccountGroupRateReader.AccountGroupRate(52064L, new BigDecimal("0.5"), 32L,
                        "pro", new BigDecimal("0.30")))));
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
    void comparesAgainstEffectiveRatioAndMarksDips() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "1.50"));

        assertThat(rows).hasSize(2);
        RateChangeImpactService.ImpactRow first = rows.get(0);
        assertThat(first.accountName()).isEqualTo("满血");
        assertThat(first.groupName()).isEqualTo("ClaudeCode MAX20");
        assertThat(first.groupRatio()).isEqualByComparingTo("1.25");
        assertThat(first.accountRatio()).isEqualByComparingTo("0.8");
        assertThat(first.effectiveRatio()).isEqualByComparingTo("1.00");
        assertThat(first.newRatio()).isEqualByComparingTo("1.50");
        assertThat(first.affected()).isTrue();
        assertThat(first.level()).isEqualTo("CRITICAL");
        assertThat(RateChangeImpactService.hasAffected(rows)).isTrue();
        assertThat(RateChangeImpactService.overallLevel(rows)).isEqualTo("CRITICAL");
    }

    @Test
    void marksOnlyGroupsWhoseEffectiveRatioIsBelowTheNewRatio() {
        // 新倍率 0.50：高于 pro 的结算 0.15，低于 ClaudeCode 的结算 1.00
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "0.50"));

        assertThat(rows).hasSize(2);
        RateChangeImpactService.ImpactRow pro = rows.get(0);
        assertThat(pro.groupName()).isEqualTo("pro");
        assertThat(pro.effectiveRatio()).isEqualByComparingTo("0.150");
        assertThat(pro.affected()).isTrue();
        RateChangeImpactService.ImpactRow claude = rows.get(1);
        assertThat(claude.groupName()).isEqualTo("ClaudeCode MAX20");
        assertThat(claude.affected()).isFalse();
    }

    @Test
    void keepsWarningWhenIncreaseStaysBelowEveryEffectiveRatio() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "0.12"));

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