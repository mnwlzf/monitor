package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.UpstreamGroupSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分组同步的身份与恢复语义。
 *
 * <p>回归线上错误：分组下线后再次出现时，旧实现只查 active 分组，误判为新分组后
 * INSERT 撞上 {@code uk_upstream_groups_identity}。这里固定住三条约束：</p>
 * <ul>
 *   <li>已下线分组必须参与增量匹配并原地恢复；</li>
 *   <li>同一批响应重复返回同一外部分组时只处理一次；</li>
 *   <li>已经下线的分组不能每轮重复记录移除。</li>
 * </ul>
 */
class UpstreamGroupSyncTest {

    private final UpstreamGroupRepository groupRepository = mock(UpstreamGroupRepository.class);
    private final UpstreamGroupSnapshotRepository snapshotRepository =
            mock(UpstreamGroupSnapshotRepository.class);
    private final UpstreamChangeRecorder changeRecorder = mock(UpstreamChangeRecorder.class);

    private UpstreamGroupSync sync;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        sync = new UpstreamGroupSync(groupRepository, snapshotRepository, changeRecorder,
                mock(CollectionJson.class));
        account = new AccountEntity();
        account.setId(8);
        when(changeRecorder.inUseChannelNames(8)).thenReturn(Set.of());
    }

    @Test
    void restoresPreviouslyInactiveGroupInsteadOfInsertingAgain() {
        UpstreamGroupEntity inactive = group(91L, false);
        when(groupRepository.findByAccountAndPlatformType(8, "sub2api", false))
                .thenReturn(List.of(inactive));

        sync.sync(account, "sub2api", 100L, List.of(snapshot("91")), "Sub2API");

        assertThat(inactive.getIsActive()).isTrue();
        assertThat(inactive.getId()).isEqualTo(91L);
        verify(groupRepository).save(inactive);
        verify(changeRecorder).recordGroupChange(eq(account), eq("sub2api"), eq(100L), eq(inactive),
                eq(UpstreamChangeTypes.GROUP_UPDATED), eq("is_active"), eq(false), eq(true),
                any(String.class), eq(Set.of()));
    }

    @Test
    void deduplicatesRepeatedExternalGroupIdsInOneResponse() {
        when(groupRepository.findByAccountAndPlatformType(8, "sub2api", false)).thenReturn(List.of());

        sync.sync(account, "sub2api", 101L,
                List.of(snapshot("91"), snapshot("91")), "Sub2API");

        verify(groupRepository, times(1)).save(any(UpstreamGroupEntity.class));
        verify(snapshotRepository, times(1)).save(any());
        verify(changeRecorder, times(1)).recordGroupChange(any(), any(), any(), any(), any(),
                any(), any(), any(), any(String.class), any());
    }

    @Test
    void doesNotDeactivateGroupThatWasAlreadyInactive() {
        UpstreamGroupEntity active = group(90L, true);
        UpstreamGroupEntity inactive = group(91L, false);
        when(groupRepository.findByAccountAndPlatformType(8, "sub2api", false))
                .thenReturn(List.of(active, inactive));

        sync.sync(account, "sub2api", 102L, List.of(snapshot("90")), "Sub2API");

        verify(groupRepository).save(active);
        verify(groupRepository, never()).save(inactive);
        verify(changeRecorder, never()).recordGroupChange(any(), any(), any(), eq(inactive),
                eq(UpstreamChangeTypes.GROUP_REMOVED), any(), any(), any(), any(String.class), any());
    }

    private static UpstreamGroupEntity group(Long id, boolean active) {
        UpstreamGroupEntity group = new UpstreamGroupEntity();
        group.setId(id);
        group.setAccountId(8);
        group.setPlatformType("sub2api");
        group.setExternalGroupId(String.valueOf(id));
        group.setGroupName("分组 " + id);
        group.setDescription("desc");
        group.setPlatform("openai");
        group.setCurrentRatio(new BigDecimal("1.2"));
        group.setStatus("ACTIVE");
        group.setIsActive(active);
        group.setFirstSeenAt(OffsetDateTime.parse("2026-10-01T00:00:00+08:00"));
        group.setLastSeenAt(OffsetDateTime.parse("2026-10-23T00:00:00+08:00"));
        group.setLastChangedAt(OffsetDateTime.parse("2026-10-23T00:00:00+08:00"));
        group.setMetadata("{}");
        return group;
    }

    private static UpstreamGroupSync.GroupSnapshot snapshot(String externalId) {
        return new UpstreamGroupSync.GroupSnapshot(
                externalId, "分组 " + externalId, "desc", "openai",
                new BigDecimal("1.2"), null, "ACTIVE", "{}");
    }
}