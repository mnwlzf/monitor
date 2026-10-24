package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.UpstreamGroupSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupSnapshotEntity;
import com.monitor.platform.common.util.Sha256Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 渠道 / 分组增量同步。
 *
 * <p>New API 与 Sub2API 的渠道采集骨架完全一致：拉全量 → 按外部 ID 对比上一轮 →
 * 记录变更 → 写快照 → 把本轮未出现的标记下线。差异只在「上游响应怎么映射成
 * {@link GroupSnapshot}」，因此各平台的采集器只负责映射，这里负责同步。</p>
 */
@Component
public class UpstreamGroupSync {

    private static final Logger log = LoggerFactory.getLogger(UpstreamGroupSync.class);

    private final UpstreamGroupRepository groupRepository;
    private final UpstreamGroupSnapshotRepository groupSnapshotRepository;
    private final UpstreamChangeRecorder changeRecorder;
    private final CollectionJson json;

    public UpstreamGroupSync(UpstreamGroupRepository groupRepository,
                             UpstreamGroupSnapshotRepository groupSnapshotRepository,
                             UpstreamChangeRecorder changeRecorder,
                             CollectionJson json) {
        this.groupRepository = groupRepository;
        this.groupSnapshotRepository = groupSnapshotRepository;
        this.changeRecorder = changeRecorder;
        this.json = json;
    }

    /**
     * 用本轮上游返回的分组列表刷新本地状态，并记录变更。
     *
     * @param platformLabel 日志里的平台名（New API / Sub2API）
     */
    public void sync(AccountEntity account, String platformType, Long collectionRunId,
                     List<GroupSnapshot> groups, String platformLabel) {
        // 必须把已下线分组也读出来：它们再次出现时应恢复原行，而不是重新 INSERT 撞唯一键。
        Map<String, UpstreamGroupEntity> existing = groupMap(account.getId(), platformType);
        Map<String, GroupSnapshot> groupsByExternalId = new LinkedHashMap<>();
        for (GroupSnapshot group : groups) {
            if (group.externalId() != null && !group.externalId().isBlank()) {
                groupsByExternalId.put(group.externalId(), group);
            }
        }
        // 「我正在使用」的渠道：渠道里有本平台号池账号绑定的 Key（一次查完，逐条判定不再打库）
        Set<String> inUseChannelNames = changeRecorder.inUseChannelNames(account.getId());
        Set<String> activeExternalIds = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        int added = 0;
        int updated = 0;

        for (GroupSnapshot group : groupsByExternalId.values()) {
            String externalGroupId = group.externalId();
            activeExternalIds.add(externalGroupId);

            UpstreamGroupEntity entity = existing.get(externalGroupId);
            boolean isNew = entity == null;
            boolean restored = !isNew && !Boolean.TRUE.equals(entity.getIsActive());
            if (isNew) {
                entity = newGroup(account, platformType, externalGroupId, now);
            }

            boolean changed = isNew || restored;
            if (!isNew) {
                changed = recordGroupChanges(account, platformType, collectionRunId, entity,
                        externalGroupId, group.ratio(), group.baseRatio(), group.status(),
                        group.name(), group.platform(), group.description(), inUseChannelNames) || changed;
            }

            entity.setGroupName(group.name());
            entity.setDescription(group.description());
            entity.setPlatform(group.platform());
            entity.setCurrentRatio(group.ratio());
            entity.setCurrentBaseRatio(group.baseRatio());
            entity.setStatus(group.status());
            entity.setIsActive(true);
            entity.setLastSeenAt(now);
            entity.setMetadata(group.rawData());
            if (changed) {
                entity.setLastChangedAt(now);
            }
            groupRepository.save(entity);

            if (isNew) {
                added++;
                changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.GROUP_ADDED, null, null, group.rawData(),
                        "渠道新增: " + group.name(), inUseChannelNames);
            } else if (restored) {
                updated++;
                changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.GROUP_UPDATED, "is_active", false, true,
                        "渠道恢复: " + group.name(), inUseChannelNames);
            } else if (changed) {
                updated++;
            }

            saveGroupSnapshot(entity, collectionRunId, group.ratio(), group.baseRatio(),
                    group.status(), true, group.rawData());
            // 同一批响应如果重复返回同一外部分组，后续项按更新处理，不再尝试插入。
            existing.put(externalGroupId, entity);
        }

        int removed = deactivateMissingGroups(account, platformType, collectionRunId,
                existing, activeExternalIds, now, inUseChannelNames);
        log.info("{} 分组同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                platformLabel, account.getId(), groupsByExternalId.size(), added, updated, removed);
    }

    /**
     * 查询账号指定平台类型下的全部分组（含已下线），并以外部分组 ID 建立索引。
     */
    private Map<String, UpstreamGroupEntity> groupMap(Integer accountId, String platformType) {
        Map<String, UpstreamGroupEntity> result = new HashMap<>();
        for (UpstreamGroupEntity group : groupRepository.findByAccountAndPlatformType(
                accountId, platformType, false)) {
            result.put(group.getExternalGroupId(), group);
        }
        return result;
    }

    /**
     * 创建新发现的分组实体，并初始化首次发现和最后可见时间。
     */
    private UpstreamGroupEntity newGroup(AccountEntity account, String platformType,
                                         String externalGroupId, OffsetDateTime now) {
        UpstreamGroupEntity entity = new UpstreamGroupEntity();
        entity.setAccountId(account.getId());
        entity.setPlatformType(platformType);
        entity.setExternalGroupId(externalGroupId);
        entity.setIsActive(true);
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setLastChangedAt(now);
        return entity;
    }

    /**
     * 将本轮上游未返回的分组标记为下线，并记录下线事件和快照。
     *
     * @return 本轮下线的分组数量
     */
    private int deactivateMissingGroups(AccountEntity account, String platformType,
                                        Long collectionRunId,
                                        Map<String, UpstreamGroupEntity> existing,
                                        Set<String> activeExternalIds,
                                        OffsetDateTime now,
                                        Set<String> inUseChannelNames) {
        int removed = 0;
        for (UpstreamGroupEntity group : existing.values()) {
            if (!Boolean.TRUE.equals(group.getIsActive())) {
                continue;
            }
            if (activeExternalIds.contains(group.getExternalGroupId())) {
                continue;
            }
            group.setIsActive(false);
            group.setLastChangedAt(now);
            groupRepository.save(group);
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, group,
                    UpstreamChangeTypes.GROUP_REMOVED, "is_active", true, false,
                    "渠道已下线: " + group.getGroupName(), inUseChannelNames);
            saveGroupSnapshot(group, collectionRunId, group.getCurrentRatio(),
                    group.getCurrentBaseRatio(), group.getStatus(), false, "{}");
            removed++;
        }
        return removed;
    }

    /**
     * 对比分组本轮数据与上一轮状态，逐字段写入变更事件。
     *
     * @return 是否至少发生一项变更
     */
    private boolean recordGroupChanges(AccountEntity account, String platformType,
                                       Long collectionRunId, UpstreamGroupEntity entity,
                                       String externalGroupId, BigDecimal ratio,
                                       BigDecimal baseRatio, String status,
                                       String groupName, String platform, String description,
                                       Set<String> inUseChannelNames) {
        boolean changed = false;
        if (CollectionValues.different(entity.getCurrentRatio(), ratio)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.RATE_CHANGED, "ratio", entity.getCurrentRatio(), ratio,
                    "倍率变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        if (CollectionValues.different(entity.getCurrentBaseRatio(), baseRatio)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.BASE_RATE_CHANGED, "base_ratio", entity.getCurrentBaseRatio(),
                    baseRatio, "基础倍率变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        if (!Objects.equals(entity.getStatus(), status)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.STATUS_CHANGED, "status", entity.getStatus(), status,
                    "状态变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        if (!Objects.equals(entity.getGroupName(), groupName)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.GROUP_UPDATED, "name", entity.getGroupName(), groupName,
                    "渠道名称变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        if (!Objects.equals(entity.getPlatform(), platform)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.GROUP_UPDATED, "platform", entity.getPlatform(), platform,
                    "渠道平台变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        if (!Objects.equals(entity.getDescription(), description)) {
            changeRecorder.recordGroupChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.GROUP_UPDATED, "description", entity.getDescription(),
                    description, "渠道描述变化: " + externalGroupId, inUseChannelNames);
            changed = true;
        }
        return changed;
    }

    /**
     * 保存一次分组状态快照，用于历史查询和变更追溯。
     */
    private void saveGroupSnapshot(UpstreamGroupEntity group, Long collectionRunId,
                                   BigDecimal ratio, BigDecimal baseRatio, String status,
                                   boolean active, String rawData) {
        UpstreamGroupSnapshotEntity snapshot = new UpstreamGroupSnapshotEntity();
        snapshot.setGroupId(group.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setRatio(ratio);
        snapshot.setBaseRatio(baseRatio);
        snapshot.setStatus(status);
        snapshot.setIsActive(active);
        snapshot.setRawData(rawData);
        snapshot.setContentHash(Sha256Util.hex(rawData));
        groupSnapshotRepository.save(snapshot);
    }

    /**
     * 各平台把上游响应归一化后的一个分组。
     *
     * @param externalId  上游分组 ID
     * @param name        展示名（New API 用分组键，Sub2API 用分组名）
     * @param description 描述
     * @param platform    上游平台（Sub2API 有，New API 为 null）
     * @param ratio       当前倍率
     * @param baseRatio   基础倍率（New API 有，Sub2API 为 null）
     * @param status      上游状态
     * @param rawData     原始响应 JSON
     */
    public record GroupSnapshot(String externalId, String name, String description, String platform,
                                BigDecimal ratio, BigDecimal baseRatio, String status, String rawData) {
    }
}
