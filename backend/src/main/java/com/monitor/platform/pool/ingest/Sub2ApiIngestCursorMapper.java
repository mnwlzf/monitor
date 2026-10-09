package com.monitor.platform.pool.ingest;

import org.apache.ibatis.annotations.Param;

/**
 * 直连库增量采集游标 Mapper（游标存在 monitor 自己的库里，与只读源无关）。
 */
public interface Sub2ApiIngestCursorMapper {

    /** 读取当前游标；没有记录时返回 null。 */
    Long selectLastUsageLogId();

    /** 游标最后一次推进时间；从未成功拉取过时为 null。 */
    java.time.OffsetDateTime selectLastRunAt();

    /** 写入/推进游标。 */
    int upsertLastUsageLogId(@Param("lastUsageLogId") Long lastUsageLogId);
}