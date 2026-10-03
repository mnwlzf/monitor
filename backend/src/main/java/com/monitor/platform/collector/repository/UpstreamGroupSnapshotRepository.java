package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.UpstreamGroupSnapshotEntity;
import com.monitor.platform.collector.repository.mapper.UpstreamGroupSnapshotMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 上游渠道/分组快照持久化仓储。
 */
@Repository
public class UpstreamGroupSnapshotRepository {

    private final UpstreamGroupSnapshotMapper snapshotMapper;

    public UpstreamGroupSnapshotRepository(UpstreamGroupSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 保存渠道快照；未指定采集时间时使用当前时间。
     */
    public UpstreamGroupSnapshotEntity save(UpstreamGroupSnapshotEntity entity) {
        if (entity.getCollectedAt() == null) {
            entity.setCollectedAt(OffsetDateTime.now());
        }
        snapshotMapper.insertSnapshot(entity);
        return entity;
    }

    /**
     * 查询渠道在时间区间内的历史快照，并限制最大返回数量。
     */
    public List<UpstreamGroupSnapshotEntity> findByGroup(Long groupId,
                                                         OffsetDateTime from,
                                                         OffsetDateTime to,
                                                         int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10000));
        return snapshotMapper.selectSnapshotsByGroupRange(groupId, from, to, safeLimit);
    }
}