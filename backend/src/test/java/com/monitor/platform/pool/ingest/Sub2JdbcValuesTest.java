package com.monitor.platform.pool.ingest;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sub2API 只读库行取值契约。
 *
 * <p>上游时间列可能是 timestamptz 也可能是无时区的 timestamp，两种都要能读，
 * 无时区的一律按业务时区（东八区）解释。</p>
 */
class Sub2JdbcValuesTest {

    private ResultSet resultSetFallingBackTo(Object value) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("created_at", OffsetDateTime.class))
                .thenThrow(new SQLException("conversion not supported"));
        when(rs.getObject("created_at")).thenReturn(value);
        return rs;
    }

    @Test
    void readsOffsetDateTimeColumnDirectly() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        OffsetDateTime value = OffsetDateTime.parse("2026-10-09T16:00:00+08:00");
        when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(value);

        assertThat(Sub2JdbcValues.offsetDateTimeOrNull(rs, "created_at")).isEqualTo(value);
    }

    @Test
    void fallsBackToLocalDateTimeInBusinessZone() throws Exception {
        ResultSet rs = resultSetFallingBackTo(LocalDateTime.of(2026, 10, 9, 16, 0, 0));

        assertThat(Sub2JdbcValues.offsetDateTimeOrNull(rs, "created_at"))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T16:00:00+08:00"));
    }

    @Test
    void fallsBackToTimestampInBusinessZone() throws Exception {
        ResultSet rs = resultSetFallingBackTo(Timestamp.valueOf(LocalDateTime.of(2026, 10, 9, 16, 0, 0)));

        assertThat(Sub2JdbcValues.offsetDateTimeOrNull(rs, "created_at"))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T16:00:00+08:00"));
    }

    @Test
    void returnsNullForUnsupportedType() throws Exception {
        ResultSet rs = resultSetFallingBackTo("weird");

        assertThat(Sub2JdbcValues.offsetDateTimeOrNull(rs, "created_at")).isNull();
    }
}
