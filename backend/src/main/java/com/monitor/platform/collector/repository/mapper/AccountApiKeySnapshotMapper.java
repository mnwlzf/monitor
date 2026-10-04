package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.AccountApiKeySnapshotEntity;

/**
 * API Key 用量快照 Mapper。
 */
public interface AccountApiKeySnapshotMapper {

    /** 新增密钥用量快照。 */
    int insertSnapshot(AccountApiKeySnapshotEntity entity);
}
