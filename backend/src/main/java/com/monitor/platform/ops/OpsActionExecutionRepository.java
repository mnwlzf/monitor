package com.monitor.platform.ops;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/** 自愈动作执行仓储。 */
@Repository
public class OpsActionExecutionRepository {

    private final OpsActionExecutionMapper mapper;

    public OpsActionExecutionRepository(OpsActionExecutionMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(OpsActionExecutionEntity entity) {
        mapper.insert(entity);
    }

    public void markFinished(Long id, String status, String responseJson,
                             String errorMessage, OffsetDateTime finishedAt) {
        mapper.markFinished(id, status, responseJson, errorMessage, finishedAt);
    }
}