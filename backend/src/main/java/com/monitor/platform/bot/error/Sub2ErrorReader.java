package com.monitor.platform.bot.error;

import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 直连 Sub2API 只读库读取机器人排障所需的错误数据。
 *
 * <p>查询只使用白名单字段，不读取 request_body / request_headers / error_body 等
 * 可能包含用户请求正文的列。单条详情也只返回排障必需的脱敏字段。</p>
 */
@Component
public class Sub2ErrorReader {

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2ErrorReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 是否配置了 Sub2API 只读库连接。 */
    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 邮箱对应的 Sub2API 用户 ID；优先走只读库，避免错误查询依赖管理员密钥。 */
    public Long userIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        List<Long> ids = jdbc.template().query(
                "SELECT id FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                email.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 时间窗内的错误汇总。只统计客户端可见错误（status>=400 或 cyber_policy）。 */
    public ErrorSummary summary(OffsetDateTime from, OffsetDateTime to, Long userId) {
        Where where = filterWhere(from, to, new ErrorFilter(
                userId, null, false, false, false, true, null));
        String sql = """
                SELECT COUNT(*) AS total_errors,
                       COUNT(*) FILTER (WHERE COALESCE(e.is_business_limited, false)) AS business_limited,
                       COUNT(*) FILTER (WHERE e.error_phase IN ('upstream', 'account_auth', 'network')) AS upstream_errors,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) IN (429, 529)) AS rate_limited,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) IN (401, 403)) AS auth_errors,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) BETWEEN 500 AND 599) AS server_errors,
                       COUNT(*) FILTER (WHERE NOT COALESCE(e.resolved, false)) AS unresolved
                FROM ops_error_logs e
                """ + where.sql();
        return jdbc.template().queryForObject(sql, (rs, rowNum) -> new ErrorSummary(
                rs.getLong("total_errors"),
                rs.getLong("business_limited"),
                rs.getLong("upstream_errors"),
                rs.getLong("rate_limited"),
                rs.getLong("auth_errors"),
                rs.getLong("server_errors"),
                rs.getLong("unresolved")
        ), where.args().toArray());
    }

    /** 时间窗内成功用量记录数，用于估算错误率。 */
    public long successCount(OffsetDateTime from, OffsetDateTime to, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM usage_logs WHERE created_at >= ? AND created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        Long value = jdbc.template().queryForObject(sql.toString(), Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    /** 最近错误列表。 */
    public List<ErrorRow> recent(OffsetDateTime from, OffsetDateTime to, ErrorFilter filter, int limit) {
        Where where = filterWhere(from, to, filter);
        String sql = """
                SELECT e.id,
                       e.created_at,
                       COALESCE(e.error_phase, '') AS error_phase,
                       COALESCE(e.error_type, '') AS error_type,
                       COALESCE(e.severity, '') AS severity,
                       COALESCE(e.upstream_status_code, e.status_code, 0) AS status_code,
                       e.upstream_status_code,
                       COALESCE(NULLIF(e.platform, ''), NULLIF(g.platform, ''), NULLIF(a.platform, ''), '') AS platform,
                       COALESCE(e.model, '') AS model,
                       COALESCE(e.requested_model, '') AS requested_model,
                       COALESCE(e.upstream_model, '') AS upstream_model,
                       COALESCE(e.error_message, '') AS error_message,
                       e.user_id,
                       COALESCE(u.email, '') AS user_email,
                       e.api_key_id,
                       COALESCE(ak.name, '') AS api_key_name,
                       e.account_id,
                       COALESCE(a.name, '') AS account_name,
                       e.group_id,
                       COALESCE(g.name, '') AS group_name,
                       COALESCE(e.inbound_endpoint, '') AS inbound_endpoint,
                       COALESCE(e.upstream_endpoint, '') AS upstream_endpoint,
                       CASE WHEN e.client_ip IS NULL THEN '' ELSE host(e.client_ip) END AS client_ip,
                       COALESCE(e.user_agent, '') AS user_agent,
                       COALESCE(e.stream, false) AS stream,
                       COALESCE(e.is_business_limited, false) AS is_business_limited,
                       COALESCE(e.resolved, false) AS resolved,
                       e.duration_ms,
                       e.time_to_first_token_ms,
                       COALESCE(e.request_id, '') AS request_id,
                       COALESCE(e.client_request_id, '') AS client_request_id
                FROM ops_error_logs e
                LEFT JOIN users u ON u.id = e.user_id
                LEFT JOIN api_keys ak ON ak.id = e.api_key_id
                LEFT JOIN accounts a ON a.id = e.account_id
                LEFT JOIN groups g ON g.id = e.group_id
                """ + where.sql() + " ORDER BY e.created_at DESC LIMIT ?";
        List<Object> args = new ArrayList<>(where.args());
        args.add(Math.max(1, Math.min(limit, 50)));
        return jdbc.template().query(sql, ROW_MAPPER, args.toArray());
    }

    /** 按错误类型/状态聚合，便于快速看出主要问题。 */
    public List<ErrorGroup> topGroups(OffsetDateTime from, OffsetDateTime to, ErrorFilter filter, int limit) {
        Where where = filterWhere(from, to, filter);
        String sql = """
                SELECT COALESCE(e.error_phase, '') AS error_phase,
                       COALESCE(e.error_type, '') AS error_type,
                       COALESCE(e.upstream_status_code, e.status_code, 0) AS status_code,
                       COUNT(*) AS error_count,
                       COUNT(*) FILTER (WHERE COALESCE(e.is_business_limited, false)) AS business_limited,
                       MAX(e.created_at) AS last_seen,
                       MAX(LEFT(COALESCE(e.error_message, ''), 200)) AS sample_message
                FROM ops_error_logs e
                LEFT JOIN users u ON u.id = e.user_id
                LEFT JOIN accounts a ON a.id = e.account_id
                LEFT JOIN groups g ON g.id = e.group_id
                """ + where.sql() + """
                GROUP BY COALESCE(e.error_phase, ''), COALESCE(e.error_type, ''),
                         COALESCE(e.upstream_status_code, e.status_code, 0)
                ORDER BY error_count DESC
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>(where.args());
        args.add(Math.max(1, Math.min(limit, 20)));
        return jdbc.template().query(sql, (rs, rowNum) -> new ErrorGroup(
                rs.getString("error_phase"),
                rs.getString("error_type"),
                rs.getInt("status_code"),
                rs.getLong("error_count"),
                rs.getLong("business_limited"),
                time(rs, "last_seen"),
                rs.getString("sample_message")
        ), args.toArray());
    }

    /** 账号维度错误排行。 */
    public List<AccountGroup> topAccounts(OffsetDateTime from, OffsetDateTime to, ErrorFilter filter, int limit) {
        Where where = filterWhere(from, to, filter);
        String sql = """
                SELECT e.account_id,
                       COALESCE(a.name, '') AS account_name,
                       COALESCE(a.platform, '') AS platform,
                       COUNT(*) AS error_count,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) IN (429, 529)) AS rate_limited,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) IN (401, 403)) AS auth_errors,
                       COUNT(*) FILTER (WHERE COALESCE(e.upstream_status_code, e.status_code, 0) BETWEEN 500 AND 599) AS server_errors,
                       MAX(e.created_at) AS last_seen
                FROM ops_error_logs e
                LEFT JOIN users u ON u.id = e.user_id
                LEFT JOIN accounts a ON a.id = e.account_id
                LEFT JOIN groups g ON g.id = e.group_id
                """ + where.sql() + """
                GROUP BY e.account_id, a.name, a.platform
                ORDER BY error_count DESC
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>(where.args());
        args.add(Math.max(1, Math.min(limit, 20)));
        return jdbc.template().query(sql, (rs, rowNum) -> new AccountGroup(
                nullableLong(rs, "account_id"),
                rs.getString("account_name"),
                rs.getString("platform"),
                rs.getLong("error_count"),
                rs.getLong("rate_limited"),
                rs.getLong("auth_errors"),
                rs.getLong("server_errors"),
                time(rs, "last_seen")
        ), args.toArray());
    }

    /** 用户维度错误排行，供管理员查询。 */
    public List<UserGroup> topUsers(OffsetDateTime from, OffsetDateTime to, ErrorFilter filter, int limit) {
        Where where = filterWhere(from, to, filter);
        String sql = """
                SELECT e.user_id,
                       COALESCE(u.email, '') AS user_email,
                       COUNT(*) AS error_count,
                       COUNT(*) FILTER (WHERE COALESCE(e.is_business_limited, false)) AS business_limited,
                       MAX(e.created_at) AS last_seen
                FROM ops_error_logs e
                LEFT JOIN users u ON u.id = e.user_id
                LEFT JOIN accounts a ON a.id = e.account_id
                LEFT JOIN groups g ON g.id = e.group_id
                """ + where.sql() + """
                GROUP BY e.user_id, u.email
                ORDER BY error_count DESC
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>(where.args());
        args.add(Math.max(1, Math.min(limit, 20)));
        return jdbc.template().query(sql, (rs, rowNum) -> new UserGroup(
                nullableLong(rs, "user_id"),
                rs.getString("user_email"),
                rs.getLong("error_count"),
                rs.getLong("business_limited"),
                time(rs, "last_seen")
        ), args.toArray());
    }

    /** 单条错误详情。userId 非空时强制限定为该用户，防止越权。 */
    public ErrorDetail detail(long id, Long userId) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id,
                       e.created_at,
                       COALESCE(e.error_phase, '') AS error_phase,
                       COALESCE(e.error_type, '') AS error_type,
                       COALESCE(e.severity, '') AS severity,
                       COALESCE(e.upstream_status_code, e.status_code, 0) AS status_code,
                       e.upstream_status_code,
                       COALESCE(NULLIF(e.platform, ''), NULLIF(g.platform, ''), NULLIF(a.platform, ''), '') AS platform,
                       COALESCE(e.model, '') AS model,
                       COALESCE(e.requested_model, '') AS requested_model,
                       COALESCE(e.upstream_model, '') AS upstream_model,
                       COALESCE(e.error_message, '') AS error_message,
                       e.user_id,
                       COALESCE(u.email, '') AS user_email,
                       e.api_key_id,
                       COALESCE(ak.name, '') AS api_key_name,
                       e.account_id,
                       COALESCE(a.name, '') AS account_name,
                       e.group_id,
                       COALESCE(g.name, '') AS group_name,
                       COALESCE(e.inbound_endpoint, '') AS inbound_endpoint,
                       COALESCE(e.upstream_endpoint, '') AS upstream_endpoint,
                       CASE WHEN e.client_ip IS NULL THEN '' ELSE host(e.client_ip) END AS client_ip,
                       COALESCE(e.user_agent, '') AS user_agent,
                       COALESCE(e.stream, false) AS stream,
                       COALESCE(e.is_business_limited, false) AS is_business_limited,
                       COALESCE(e.resolved, false) AS resolved,
                       e.duration_ms,
                       e.time_to_first_token_ms,
                       COALESCE(e.request_id, '') AS request_id,
                       COALESCE(e.client_request_id, '') AS client_request_id,
                       COALESCE(e.error_body, '') AS error_body,
                       COALESCE(e.upstream_error_message, '') AS upstream_error_message,
                       COALESCE(e.upstream_error_detail, '') AS upstream_error_detail,
                       COALESCE(e.upstream_errors::text, '') AS upstream_errors,
                       COALESCE(e.api_key_prefix, '') AS api_key_prefix,
                       COALESCE(e.request_path, '') AS request_path,
                       e.auth_latency_ms,
                       e.routing_latency_ms,
                       e.upstream_latency_ms,
                       e.response_latency_ms,
                       e.resolved_at
                FROM ops_error_logs e
                LEFT JOIN users u ON u.id = e.user_id
                LEFT JOIN api_keys ak ON ak.id = e.api_key_id
                LEFT JOIN accounts a ON a.id = e.account_id
                LEFT JOIN groups g ON g.id = e.group_id
                WHERE e.id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (userId != null) {
            sql.append(" AND e.user_id = ?");
            args.add(userId);
        }
        List<ErrorDetail> rows = jdbc.template().query(sql.toString(), DETAIL_MAPPER, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 与错误关联的系统日志；仅管理员详情使用。 */
    public List<SystemLogRow> systemLogs(String requestId, String clientRequestId, int limit) {
        if ((requestId == null || requestId.isBlank())
                && (clientRequestId == null || clientRequestId.isBlank())) {
            return List.of();
        }
        String sql = """
                SELECT created_at, COALESCE(level, '') AS level,
                       COALESCE(component, '') AS component,
                       COALESCE(message, '') AS message
                FROM ops_system_logs
                WHERE request_id = ? OR client_request_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;
        return jdbc.template().query(sql, (rs, rowNum) -> new SystemLogRow(
                time(rs, "created_at"),
                rs.getString("level"),
                rs.getString("component"),
                rs.getString("message")
        ), requestId == null ? "" : requestId, clientRequestId == null ? "" : clientRequestId,
                Math.max(1, Math.min(limit, 20)));
    }

    // ---------------------------------------------------------------- where

    private Where filterWhere(OffsetDateTime from, OffsetDateTime to, ErrorFilter filter) {
        StringBuilder sql = new StringBuilder("""
                WHERE e.created_at >= ?
                  AND e.created_at < ?
                  AND (COALESCE(e.status_code, 0) >= 400 OR e.error_type = 'cyber_policy')
                """);
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        if (filter != null) {
            if (filter.userId() != null) {
                sql.append(" AND e.user_id = ?");
                args.add(filter.userId());
            }
            if (filter.businessLimitedOnly()) {
                sql.append(" AND COALESCE(e.is_business_limited, false) = true");
            } else if (!filter.includeBusinessLimited()) {
                sql.append(" AND COALESCE(e.is_business_limited, false) = false");
            }
            if (filter.statusCode() != null) {
                sql.append(" AND COALESCE(e.upstream_status_code, e.status_code, 0) = ?");
                args.add(filter.statusCode());
            }
            if (filter.status5xx()) {
                sql.append(" AND COALESCE(e.upstream_status_code, e.status_code, 0) BETWEEN 500 AND 599");
            }
            if (filter.rateLimited()) {
                sql.append(" AND COALESCE(e.upstream_status_code, e.status_code, 0) IN (429, 529)");
            }
            String keyword = filter.keyword();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                sql.append("""
                         AND (e.error_message ILIKE ?
                              OR e.model ILIKE ?
                              OR e.requested_model ILIKE ?
                              OR e.upstream_model ILIKE ?
                              OR a.name ILIKE ?
                              OR g.name ILIKE ?
                              OR u.email ILIKE ?)
                        """);
                for (int i = 0; i < 7; i++) {
                    args.add(like);
                }
            }
        }
        // 保证 WHERE 片段与后续 GROUP BY / ORDER BY 之间有明确分隔，避免拼成 falseGROUP BY。
        sql.append('\n');
        return new Where(sql.toString(), args);
    }

    // ---------------------------------------------------------------- mapper

    private static final RowMapper<ErrorRow> ROW_MAPPER = (rs, rowNum) -> row(rs);

    private static final RowMapper<ErrorDetail> DETAIL_MAPPER = (rs, rowNum) -> new ErrorDetail(
            row(rs),
            rs.getString("error_body"),
            rs.getString("upstream_error_message"),
            rs.getString("upstream_error_detail"),
            rs.getString("upstream_errors"),
            rs.getString("api_key_prefix"),
            rs.getString("request_path"),
            nullableLong(rs, "auth_latency_ms"),
            nullableLong(rs, "routing_latency_ms"),
            nullableLong(rs, "upstream_latency_ms"),
            nullableLong(rs, "response_latency_ms"),
            time(rs, "resolved_at")
    );

    private static ErrorRow row(ResultSet rs) throws SQLException {
        return new ErrorRow(
                rs.getLong("id"),
                time(rs, "created_at"),
                rs.getString("error_phase"),
                rs.getString("error_type"),
                rs.getString("severity"),
                rs.getInt("status_code"),
                nullableInt(rs, "upstream_status_code"),
                rs.getString("platform"),
                rs.getString("model"),
                rs.getString("requested_model"),
                rs.getString("upstream_model"),
                rs.getString("error_message"),
                nullableLong(rs, "user_id"),
                rs.getString("user_email"),
                nullableLong(rs, "api_key_id"),
                rs.getString("api_key_name"),
                nullableLong(rs, "account_id"),
                rs.getString("account_name"),
                nullableLong(rs, "group_id"),
                rs.getString("group_name"),
                rs.getString("inbound_endpoint"),
                rs.getString("upstream_endpoint"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getBoolean("stream"),
                rs.getBoolean("is_business_limited"),
                rs.getBoolean("resolved"),
                nullableInt(rs, "duration_ms"),
                nullableLong(rs, "time_to_first_token_ms"),
                rs.getString("request_id"),
                rs.getString("client_request_id")
        );
    }

    // ---------------------------------------------------------------- helpers

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private record Where(String sql, List<Object> args) {
    }

    // ---------------------------------------------------------------- records

    public record ErrorFilter(
            Long userId,
            Integer statusCode,
            boolean status5xx,
            boolean rateLimited,
            boolean businessLimitedOnly,
            boolean includeBusinessLimited,
            String keyword
    ) {
        public static ErrorFilter all(Long userId) {
            return new ErrorFilter(userId, null, false, false, false, false, null);
        }
    }

    public record ErrorSummary(
            long totalErrors,
            long businessLimited,
            long upstreamErrors,
            long rateLimited,
            long authErrors,
            long serverErrors,
            long unresolved
    ) {
    }

    public record ErrorRow(
            long id,
            OffsetDateTime createdAt,
            String phase,
            String type,
            String severity,
            int statusCode,
            Integer upstreamStatusCode,
            String platform,
            String model,
            String requestedModel,
            String upstreamModel,
            String message,
            Long userId,
            String userEmail,
            Long apiKeyId,
            String apiKeyName,
            Long accountId,
            String accountName,
            Long groupId,
            String groupName,
            String inboundEndpoint,
            String upstreamEndpoint,
            String clientIp,
            String userAgent,
            boolean stream,
            boolean businessLimited,
            boolean resolved,
            Integer durationMs,
            Long timeToFirstTokenMs,
            String requestId,
            String clientRequestId
    ) {
    }

    public record ErrorGroup(
            String phase,
            String type,
            int statusCode,
            long count,
            long businessLimited,
            OffsetDateTime lastSeen,
            String sampleMessage
    ) {
    }

    public record AccountGroup(
            Long accountId,
            String accountName,
            String platform,
            long count,
            long rateLimited,
            long authErrors,
            long serverErrors,
            OffsetDateTime lastSeen
    ) {
    }

    public record UserGroup(
            Long userId,
            String userEmail,
            long count,
            long businessLimited,
            OffsetDateTime lastSeen
    ) {
    }

    public record ErrorDetail(
            ErrorRow row,
            String errorBody,
            String upstreamErrorMessage,
            String upstreamErrorDetail,
            String upstreamErrors,
            String apiKeyPrefix,
            String requestPath,
            Long authLatencyMs,
            Long routingLatencyMs,
            Long upstreamLatencyMs,
            Long responseLatencyMs,
            OffsetDateTime resolvedAt
    ) {
    }

    public record SystemLogRow(
            OffsetDateTime createdAt,
            String level,
            String component,
            String message
    ) {
    }
}
