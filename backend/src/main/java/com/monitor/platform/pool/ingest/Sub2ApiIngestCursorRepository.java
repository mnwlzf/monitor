package com.monitor.platform.pool.ingest;

import org.springframework.stereotype.Repository;

/**
 * 直连库增量采集游标仓储。
 */
@Repository
public class Sub2ApiIngestCursorRepository {

    private final Sub2ApiIngestCursorMapper mapper;

    public Sub2ApiIngestCursorRepository(Sub2ApiIngestCursorMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前游标，未初始化时返回 0。 */
    public long lastUsageLogId() {
        Long value = mapper.selectLastUsageLogId();
        return value == null ? 0L : value;
    }

    /** 推进游标。 */
    public void save(long lastUsageLogId) {
        mapper.upsertLastUsageLogId(lastUsageLogId);
    }
}