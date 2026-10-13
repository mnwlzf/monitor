package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游渠道倍率变化 → 我平台分组影响评估。
 *
 * <p>数据来自项目自己采集的 upstream_groups（Sub2API 的 /groups/available）：
 * 一个账号可以同时属于多个分组，每个分组带自己的倍率。</p>
 *
 * <p>核心规则：上游新倍率大于分组倍率就算「倒挂」（按这个分组卖已不划算）。</p>
 */
class RateChangeImpactServiceTest {

    private UpstreamGroupRepository groupRepository;
    private AccountRepository accountRepository;
    private RateChangeImpactService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(UpstreamGroupRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new RateChangeImpactService(groupRepository, accountRepository);

        AccountEntity account = new AccountEntity();
        account.setId(10);
        account.setDisplayName("我平台账号");
        when(accountRepository.findById(anyInt())).thenReturn(Optional.of(account));

        when(groupRepository.findByAccount(anyInt(), anyBoolean())).thenReturn(List.of(
                group("g-a", "渠道A", "1.25"),
                group("g-b", "pro", "0.30")));
    }

    private static UpstreamGroupEntity group(String externalId, String name, String ratio) {
        UpstreamGroupEntity entity = new UpstreamGroupEntity();
        entity.setAccountId(10);
        entity.setExternalGroupId(externalId);
        entity.setGroupName(name);
        entity.setCurrentRatio(new BigDecimal(ratio));
        entity.setIsActive(true);
        return entity;
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
    void marksGroupsWhereUpstreamCostBeatsGroupRatio() {
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "1.50"));

        assertThat(rows).hasSize(2);
        // 倍率高的分组排前面
        RateChangeImpactService.ImpactRow first = rows.get(0);
        assertThat(first.accountName()).isEqualTo("我平台账号");
        assertThat(first.groupName()).isEqualTo("渠道A");
        assertThat(first.groupRatio()).isEqualByComparingTo("1.25");
        assertThat(first.oldRatio()).isEqualByComparingTo("0.10");
        assertThat(first.newRatio()).isEqualByComparingTo("1.50");
        assertThat(first.affected()).isTrue();
        assertThat(first.level()).isEqualTo("CRITICAL");
        assertThat(rows).allMatch(RateChangeImpactService.ImpactRow::affected);
        assertThat(RateChangeImpactService.hasAffected(rows)).isTrue();
        assertThat(RateChangeImpactService.overallLevel(rows)).isEqualTo("CRITICAL");
    }

    @Test
    void marksOnlyGroupsBelowTheNewRatio() {
        // 新倍率 0.50：低于 1.25 的渠道A，但高于 0.30 的 pro
        List<RateChangeImpactService.ImpactRow> rows = service.impactOf(rateEvent("0.10", "0.50"));

        assertThat(rows).hasSize(2);
        // 受影响的排前面：pro 倒挂，渠道A 没有
        RateChangeImpactService.ImpactRow pro = rows.get(0);
        assertThat(pro.groupName()).isEqualTo("pro");
        assertThat(pro.affected()).isTrue();
        assertThat(pro.level()).isEqualTo("CRITICAL");
        RateChangeImpactService.ImpactRow channel = rows.get(1);
        assertThat(channel.groupName()).isEqualTo("渠道A");
        assertThat(channel.affected()).isFalse();
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
    void ignoresNonRateChangeEvents() {
        assertThat(service.impactOf(event("GROUP_UPDATED", "渠道A", null, "渠道改名"))).isEmpty();
    }
}