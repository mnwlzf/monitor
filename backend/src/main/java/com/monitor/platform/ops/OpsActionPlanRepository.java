package com.monitor.platform.ops;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 自愈动作计划仓储。 */
@Repository
public class OpsActionPlanRepository {

    private final OpsActionPlanMapper mapper;

    public OpsActionPlanRepository(OpsActionPlanMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<OpsActionPlanEntity> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public List<OpsActionPlanEntity> recent(int limit) {
        return mapper.selectRecent(Math.max(1, Math.min(limit, 200)));
    }

    public void insert(OpsActionPlanEntity entity) {
        mapper.insert(entity);
    }

    public boolean claimForExecution(Long id, String confirmationCode, String approvedBy,
                                     OffsetDateTime approvedAt, OffsetDateTime updatedAt) {
        return mapper.claimForExecution(id, confirmationCode, approvedBy, approvedAt, updatedAt) == 1;
    }

    public void markExecuted(Long id, String beforeJson, String afterJson,
                             OffsetDateTime executedAt, OffsetDateTime updatedAt) {
        mapper.markExecuted(id, beforeJson, afterJson, executedAt, updatedAt);
    }

    public void markFailed(Long id, String afterJson, OffsetDateTime updatedAt) {
        mapper.markFailed(id, afterJson, updatedAt);
    }

    public int expireStale(OffsetDateTime now, OffsetDateTime updatedAt) {
        return mapper.expireStale(now, updatedAt);
    }
}