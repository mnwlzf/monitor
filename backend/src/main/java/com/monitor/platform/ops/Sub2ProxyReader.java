package com.monitor.platform.ops;

import com.monitor.platform.pool.ingest.Sub2JdbcValues;
import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** 读取 Sub2API 代理健康数据（只读）。 */
@Component
public class Sub2ProxyReader {

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2ProxyReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    public List<ProxyRow> list() {
        if (!isAvailable()) {
            return List.of();
        }
        return jdbc.template().query("""
                SELECT id, name, protocol, status, expires_at, fallback_mode, backup_proxy_id, expiry_warn_days
                FROM proxies
                WHERE deleted_at IS NULL
                ORDER BY expires_at NULLS LAST, id
                """, (rs, rowNum) -> new ProxyRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("protocol"),
                rs.getString("status"),
                Sub2JdbcValues.offsetDateTimeOrNull(rs, "expires_at"),
                rs.getString("fallback_mode"),
                longOrNull(rs, "backup_proxy_id"),
                rs.getInt("expiry_warn_days")
        ));
    }

    private static Long longOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    public record ProxyRow(
            long id,
            String name,
            String protocol,
            String status,
            OffsetDateTime expiresAt,
            String fallbackMode,
            Long backupProxyId,
            int expiryWarnDays
    ) {
    }
}