package com.monitor.platform.ops;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 事故仓储。 */
@Repository
public class OpsIncidentRepository {

    private final OpsIncidentMapper mapper;

    public OpsIncidentRepository(OpsIncidentMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<OpsIncidentEntity> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<OpsIncidentEntity> findActiveByFingerprint(String fingerprint) {
        return Optional.ofNullable(mapper.selectActiveByFingerprint(fingerprint));
    }

    public List<OpsIncidentEntity> recent(String status, int limit) {
        return mapper.selectRecent(status, Math.max(1, Math.min(limit, 200)));
    }

    public void insert(OpsIncidentEntity entity) {
        mapper.insert(entity);
    }

    public void updateObserved(Long id, String title, String severity, String impactSummary,
                               String evidenceJson, OffsetDateTime lastSeenAt, OffsetDateTime updatedAt) {
        mapper.updateObserved(id, title, severity, impactSummary, evidenceJson, lastSeenAt, updatedAt);
    }

    public void updateStatus(Long id, String status, OffsetDateTime updatedAt) {
        mapper.updateStatus(id, status, updatedAt);
    }

    public int resolveStale(OffsetDateTime before, OffsetDateTime resolvedAt, OffsetDateTime updatedAt) {
        return mapper.resolveStale(before, resolvedAt, updatedAt);
    }
}