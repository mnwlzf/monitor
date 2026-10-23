package com.monitor.platform.bot.error;

import com.monitor.platform.bot.error.Sub2ErrorReader.ErrorFilter;
import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sub2ErrorReaderTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void topGroupsShouldSeparateWhereAndGroupBy() {
        Sub2ReadOnlyJdbc readOnlyJdbc = mock(Sub2ReadOnlyJdbc.class);
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(readOnlyJdbc.template()).thenReturn(template);
        when(template.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        Sub2ErrorReader reader = new Sub2ErrorReader(readOnlyJdbc);
        OffsetDateTime to = OffsetDateTime.now();
        reader.topGroups(to.minusHours(1), to, ErrorFilter.all(null), 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        String normalized = sql.getValue().replaceAll("\\s+", " ").trim();
        assertThat(normalized)
                .contains("false GROUP BY")
                .doesNotContain("falseGROUP BY");
    }
}
