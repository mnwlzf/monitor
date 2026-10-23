package com.monitor.platform.bot.error;

import com.monitor.platform.bot.report.BotReport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotErrorQueryAuditServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldRenderAuditSummaryAndRecentQueries() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet summary = mock(ResultSet.class);
        when(summary.getLong("total")).thenReturn(10L);
        when(summary.getLong("denied")).thenReturn(2L);
        when(summary.getLong("users")).thenReturn(3L);
        when(summary.getDouble("avg_duration")).thenReturn(45.5);

        ResultSet action = mock(ResultSet.class);
        when(action.getString("action")).thenReturn("errors");
        when(action.getLong("total")).thenReturn(8L);
        when(action.getLong("denied")).thenReturn(1L);
        when(action.getDouble("avg_duration")).thenReturn(40.0);

        ResultSet recent = mock(ResultSet.class);
        when(recent.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
        when(recent.getObject("qq_user_id", Long.class)).thenReturn(123L);
        when(recent.getString("action")).thenReturn("errors");
        when(recent.getString("query_text")).thenReturn("1h 429");
        when(recent.getBoolean("allowed")).thenReturn(true);
        when(recent.getObject("duration_ms", Integer.class)).thenReturn(12);

        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> map(invocation.getArgument(1), summary));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> List.of(map(invocation.getArgument(1), action)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(invocation -> List.of(map(invocation.getArgument(1), recent)));

        BotReport report = new BotErrorQueryAuditService(jdbc).report("24h 20");

        assertThat(report.title()).contains("错误查询审计");
        assertThat(report.toPlainText())
                .contains("限流拦截")
                .contains("errors")
                .contains("1h 429")
                .contains("放行");
    }

    private static Object map(RowMapper<?> mapper, ResultSet rs) throws SQLException {
        return mapper.mapRow(rs, 0);
    }
}
