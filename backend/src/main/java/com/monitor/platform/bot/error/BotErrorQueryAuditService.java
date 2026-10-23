package com.monitor.platform.bot.error;

import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 错误查询审计。审计失败不影响机器人回答。
 */
@Service
public class BotErrorQueryAuditService {

    private static final Logger log = LoggerFactory.getLogger(BotErrorQueryAuditService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Set<String> RANGES = Set.of(
            "5m", "15m", "30m", "90m", "1h", "6h", "12h", "24h", "7d", "30d");

    private final JdbcTemplate jdbcTemplate;

    public BotErrorQueryAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long userId, String action, String query, boolean allowed, long durationMs) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO bot_error_query_audits
                        (qq_user_id, action, query_text, allowed, duration_ms)
                    VALUES (?, ?, ?, ?, ?)
                    """, userId, action, truncate(query, 500), allowed, Math.max(0L, durationMs));
        } catch (RuntimeException ex) {
            log.warn("写入机器人错误查询审计失败: {}", ex.getMessage());
        }
    }

    /** 管理员：查看错误查询审计和限流命中情况。 */
    public BotReport report(String arg) {
        Window window = window(arg);
        int limit = limit(arg);
        try {
            Summary summary = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE NOT allowed) AS denied,
                           COUNT(DISTINCT qq_user_id) AS users,
                           COALESCE(AVG(duration_ms), 0) AS avg_duration
                    FROM bot_error_query_audits
                    WHERE created_at >= ? AND created_at < ?
                    """, (rs, rowNum) -> new Summary(
                    rs.getLong("total"),
                    rs.getLong("denied"),
                    rs.getLong("users"),
                    rs.getDouble("avg_duration")), window.from(), window.to());

            List<ActionRow> actions = jdbcTemplate.query("""
                    SELECT action,
                           COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE NOT allowed) AS denied,
                           COALESCE(AVG(duration_ms), 0) AS avg_duration
                    FROM bot_error_query_audits
                    WHERE created_at >= ? AND created_at < ?
                    GROUP BY action
                    ORDER BY total DESC
                    LIMIT 5
                    """, (rs, rowNum) -> new ActionRow(
                    rs.getString("action"),
                    rs.getLong("total"),
                    rs.getLong("denied"),
                    rs.getDouble("avg_duration")), window.from(), window.to());

            List<AuditRow> rows = jdbcTemplate.query("""
                    SELECT created_at, qq_user_id, action, query_text, allowed, duration_ms
                    FROM bot_error_query_audits
                    WHERE created_at >= ? AND created_at < ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """, (rs, rowNum) -> new AuditRow(
                    time(rs.getTimestamp("created_at")),
                    rs.getObject("qq_user_id", Long.class),
                    rs.getString("action"),
                    rs.getString("query_text"),
                    rs.getBoolean("allowed"),
                    rs.getObject("duration_ms", Integer.class)), window.from(), window.to(), limit);

            return report(window, summary == null
                    ? new Summary(0, 0, 0, 0) : summary, actions, rows);
        } catch (RuntimeException ex) {
            log.warn("查询机器人错误审计失败", ex);
            return BotReport.fromMarkdown("查询审计暂时不可用：" + ex.getMessage());
        }
    }

    private BotReport report(Window window, Summary summary,
                             List<ActionRow> actions, List<AuditRow> rows) {
        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block("概览", List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.right("值")), List.of(
                BotReport.Row.of("时间窗", window.label()),
                BotReport.Row.of("查询次数", String.valueOf(summary.total())),
                BotReport.Row.of("限流拦截", String.valueOf(summary.denied())),
                BotReport.Row.of("查询用户", String.valueOf(summary.users())),
                BotReport.Row.of("平均耗时", String.format(Locale.ROOT, "%.0f ms", summary.avgDuration()))), null));

        if (actions.isEmpty()) {
            blocks.add(BotReport.paragraph("动作排行", List.of("这个时间窗内没有审计记录。")));
        } else {
            List<BotReport.Row> actionRows = new ArrayList<>();
            for (ActionRow action : actions) {
                actionRows.add(BotReport.Row.of(
                        action.action(),
                        String.valueOf(action.total()),
                        String.valueOf(action.denied()),
                        String.format(Locale.ROOT, "%.0f ms", action.avgDuration())));
            }
            blocks.add(new BotReport.Block("动作排行", List.of(
                    BotReport.Col.left("动作"),
                    BotReport.Col.right("次数"),
                    BotReport.Col.right("拦截"),
                    BotReport.Col.right("平均耗时")), actionRows, null));
        }

        if (!rows.isEmpty()) {
            List<BotReport.Row> recentRows = new ArrayList<>();
            for (AuditRow row : rows) {
                recentRows.add(BotReport.Row.of(
                        time(row.createdAt()),
                        row.userId() == null ? "-" : String.valueOf(row.userId()),
                        row.action(),
                        row.allowed() ? "放行" : "拦截",
                        row.durationMs() == null ? "-" : row.durationMs() + " ms",
                        truncate(row.query(), 120)));
            }
            blocks.add(new BotReport.Block("最近查询", List.of(
                    BotReport.Col.left("时间"),
                    BotReport.Col.left("QQ"),
                    BotReport.Col.left("动作"),
                    BotReport.Col.left("结果"),
                    BotReport.Col.right("耗时"),
                    BotReport.Col.left("参数")), recentRows, null));
        }

        List<String> notes = new ArrayList<>();
        notes.add("错误查询默认每 QQ 用户每分钟最多 12 次。");
        notes.add("审计记录包含动作、参数摘要、是否放行和耗时；审计写入失败不会影响查询。");
        notes.add("本次查看审计的操作本身也会在回复后写入一条记录。");
        return BotReport.of("错误查询审计（" + window.label() + "）", blocks, notes);
    }

    private static Window window(String arg) {
        String raw = arg == null ? "" : arg.trim();
        String value = "24h";
        for (String token : raw.isEmpty() ? new String[0] : raw.split("\\s+")) {
            if (RANGES.contains(token.toLowerCase(Locale.ROOT))) {
                value = token.toLowerCase(Locale.ROOT);
                break;
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Duration duration = duration(value);
        return new Window(now.minus(duration), now, "近 " + value);
    }

    private static int limit(String arg) {
        if (arg == null) {
            return 20;
        }
        for (String token : arg.trim().split("\\s+")) {
            if (token.matches("\\d{1,2}") && !RANGES.contains(token)) {
                return Math.max(1, Math.min(Integer.parseInt(token), 50));
            }
        }
        return 20;
    }

    private static Duration duration(String value) {
        char unit = value.charAt(value.length() - 1);
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (unit) {
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> Duration.ofHours(24);
        };
    }

    private static OffsetDateTime time(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "-" : TIME.format(value.atZoneSameInstant(ZONE));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Window(OffsetDateTime from, OffsetDateTime to, String label) {
    }

    private record Summary(long total, long denied, long users, double avgDuration) {
    }

    private record ActionRow(String action, long total, long denied, double avgDuration) {
    }

    private record AuditRow(OffsetDateTime createdAt, Long userId, String action,
                            String query, boolean allowed, Integer durationMs) {
    }
}
