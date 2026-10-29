package com.monitor.platform.ops;

import org.springframework.stereotype.Repository;

import java.util.List;

/** 事故时间线仓储。 */
@Repository
public class OpsIncidentEventRepository {

    private final OpsIncidentEventMapper mapper;

    public OpsIncidentEventRepository(OpsIncidentEventMapper mapper) {
        this.mapper = mapper;
    }

    public void append(Long incidentId, String eventType, String actor, String content) {
        OpsIncidentEventEntity entity = new OpsIncidentEventEntity();
        entity.setIncidentId(incidentId);
        entity.setEventType(eventType);
        entity.setActor(actor);
        entity.setContent(content);
        mapper.insert(entity);
    }

    public List<OpsIncidentEventEntity> recent(Long incidentId, int limit) {
        return mapper.selectByIncidentId(incidentId, Math.max(1, Math.min(limit, 200)));
    }
}