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
 * 上游渠道涨价 → 本平台分组影响评估。
 *
 * <p>核心规则：上游新倍率大于分组倍率就算「倒挂」（成本高于售价）。</p>
 */
class RateChangeImpactServiceTest {

    private AccountApiKeyRepository apiKeyRepository;
    private PoolAccountRepository poolAccountRepository;
    private Sub2GroupRatioReader groupReader;
    private RateChangeImpactService service;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(AccountApiKeyRepository.class);
        poolAccountRepository = mock(PoolAccountRepository.class);
        groupReader = mock(Sub2GroupRatioReader.class);
        service = new RateChangeImpactService(apiKeyRepository, poolAccountRepository, groupReader);

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

        when(groupReader.isAvailable()).thenReturn(true);
        when(groupReader.groupsByAccount(any())).thenReturn(Map.of(52064L, List.of(
                new Sub2GroupRatioReader.PlatformGroup(22L, "ClaudeCode MAX20", new BigDecimal("1.25")),
                new Sub2GroupRatioReader.PlatformGroup(32L, "pro", new BigDecimal("0.30")))));
    }

    private static UpstreamChangeEventEntity rateEvent(String before, String after) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setId(1L);
        event.setAccountId(10);
        event.setEntityType("GROUP");
        event.setEntityKey("grp-1");
        event.setGroupName("渠道A");
        event.setChangeType("RATE_CHANGED");
        event.setOldValue(before);
        event.setNewValue(after);
        return event;
    }

    @Test
    void marksGroupsWhereUpstreamCostBeatsGroupRatio() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "1.50"));

        assertThat(rows).hasSize(2);
        // 受影响（1.50 > 1.25）的排最前
        RateChangeImpactService.ImpactRow first = rows.get(0);
        assertThat(first.platformAccount()).isEqualTo("满血");
        assertThat(first.groupName()).isEqualTo("ClaudeCode MAX20");
        assertThat(first.groupRatio()).isEqualByComparingTo("1.25");
        assertThat(first.upstreamChannel()).isEqualTo("渠道A");
        assertThat(first.oldRatio()).isEqualByComparingTo("0.10");
        assertThat(first.newRatio()).isEqualByComparingTo("1.50");
        assertThat(first.affected()).isTrue();
        assertThat(first.level()).isEqualTo("CRITICAL");
        assertThat(RateChangeImpactService.hasAffected(rows)).isTrue();
        assertThat(RateChangeImpactService.overallLevel(rows)).isEqualTo("CRITICAL");
    }

    @Test
    void keepsWarningWhenIncreaseStaysBelowAnyGroupRatio() {
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
    void returnsEmptyWhenReadOnlyDatabaseUnavailable() {
        when(groupReader.isAvailable()).thenReturn(false);

        assertThat(service.impactOf(rateEvent("0.10", "1.50"))).isEmpty();
    }
}