package com.monitor.platform.pool.ingest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 直连库行映射。
 *
 * <p>上游 usage_logs 的 *_tokens / api_key_id 在不同版本里可能是 int4 也可能是 int8；
 * 直接用 {@code rs.getObject(col, Long.class)} 读 int4 会抛
 * 「conversion to class java.lang.Long from int4 not supported」，整条增量链路会卡死、
 * 游标不再推进。这里固定住「两种宽度都能读」。</p>
 */
class Sub2ApiUsageLogReaderTest {

    private ResultSet resultSetWith(Object numberValue) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(9L);
        when(rs.getLong("account_id")).thenReturn(25256L);
        when(rs.getString("request_id")).thenReturn("req-1");
        when(rs.getString("model")).thenReturn("gpt-6-sol");
        when(rs.getObject("api_key_id")).thenReturn(numberValue);
        when(rs.getObject("first_token_ms")).thenReturn(120);
        when(rs.getObject("duration_ms")).thenReturn(3400);
        when(rs.getObject("input_tokens")).thenReturn(numberValue);
        when(rs.getObject("output_tokens")).thenReturn(numberValue);
        when(rs.getObject("cache_read_tokens")).thenReturn(numberValue);
        when(rs.getObject("cache_creation_tokens")).thenReturn(numberValue);
        when(rs.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-10-09T16:00:00+08:00"));
        when(rs.getBigDecimal("total_cost")).thenReturn(new BigDecimal("0.5"));
        when(rs.getBigDecimal("actual_cost")).thenReturn(new BigDecimal("0.1"));
        return rs;
    }

    @Test
    void shouldMapInt4Columns() throws Exception {
        // 上游列是 int4 时，getObject 返回的是 Integer
        Sub2ApiUsageLogRow row = Sub2ApiUsageLogReader.ROW_MAPPER.mapRow(resultSetWith(1234), 0);

        assertNotNull(row);
        assertEquals(9L, row.id());
        assertEquals(25256L, row.accountId());
        assertEquals(1234L, row.apiKeyId());
        assertEquals(120, row.firstTokenMs());
        assertEquals(3400, row.durationMs());
        assertEquals(1234L, row.inputTokens());
        assertEquals(1234L, row.cacheReadTokens());
        assertEquals(OffsetDateTime.parse("2026-10-09T16:00:00+08:00"), row.createdAt());
    }

    @Test
    void shouldMapInt8Columns() throws Exception {
        Sub2ApiUsageLogRow row = Sub2ApiUsageLogReader.ROW_MAPPER.mapRow(resultSetWith(1234L), 0);

        assertNotNull(row);
        assertEquals(1234L, row.apiKeyId());
        assertEquals(1234L, row.inputTokens());
    }
}