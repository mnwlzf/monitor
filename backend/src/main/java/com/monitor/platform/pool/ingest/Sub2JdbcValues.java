package com.monitor.platform.pool.ingest;

import com.monitor.platform.common.time.BusinessTime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Sub2API 只读库的行取值工具。
 *
 * <p>上游 {@code created_at} 等时间列在不同版本里可能是 timestamptz，也可能是无时区的
 * timestamp，两种都要能读。这段兼容逻辑此前在多个读取类里各写一份，集中到这里统一口径。</p>
 */
public final class Sub2JdbcValues {

    private Sub2JdbcValues() {
    }

    /**
     * 宽容读取时间列：带时区、无时区、瞬时三种格式都能收。
     *
     * <p>先试 {@code getObject(column, OffsetDateTime.class)}；无时区的列走不通该转换时，
     * 退回按业务时区解释。</p>
     */
    public static OffsetDateTime offsetDateTimeOrNull(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getObject(column, OffsetDateTime.class);
        } catch (SQLException ignored) {
            // 无时区的 timestamp 走不通上面的转换，退回按业务时区解释
        }
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(BusinessTime.ZONE).toOffsetDateTime();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().atZone(BusinessTime.ZONE).toOffsetDateTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(BusinessTime.ZONE).toOffsetDateTime();
        }
        return null;
    }
}
