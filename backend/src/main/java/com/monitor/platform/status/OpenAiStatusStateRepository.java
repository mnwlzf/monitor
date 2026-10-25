package com.monitor.platform.status;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/**
 * OpenAI 服务状态告警状态持久化。
 */
@Repository
public class OpenAiStatusStateRepository {

    private final OpenAiStatusStateMapper mapper;

    public OpenAiStatusStateRepository(OpenAiStatusStateMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取单行状态；升级过程中即使行不存在也能安全兜底。 */
    public OpenAiStatusStateEntity get() {
        OpenAiStatusStateEntity entity = mapper.selectState();
        if (entity == null) {
            entity = new OpenAiStatusStateEntity();
            entity.setId(1);
            entity.setAlertActive(false);
            entity.setAlertSent(false);
        }
        return entity;
    }

    /** 保存单行状态。 */
    public void save(OpenAiStatusStateEntity entity) {
        entity.setId(1);
        entity.setUpdatedAt(OffsetDateTime.now());
        mapper.upsertState(entity);
    }
}