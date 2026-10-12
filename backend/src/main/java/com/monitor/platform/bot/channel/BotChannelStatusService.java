package com.monitor.platform.bot.channel;

import com.monitor.platform.bot.channel.Sub2ChannelStatusReader.ChannelStatusRow;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「渠道状态」取数与报表：<strong>管理员与平台用户都能看</strong>，按「平台 + 分组」聚合。
 *
 * <p>这是对外的那一份：数据源是 Sub2API 被动渠道监控 v2 的聚合表
 * （见 {@link Sub2ChannelStatusReader}），已经是平台 × 分组 × 模型的统计结果，
 * 不含任何用户身份。</p>
 *
 * <p>可见范围对齐 Sub2API 的 {@code scopeFilter}：管理员看全部；
 * 普通用户只看<strong>自己有权限的分组</strong>（订阅型分组要有有效订阅，
 * 标准分组默认公开、专属或受限用户要落在 {@code user_allowed_groups} 里）。</p>
 *
 * <p>Sub2API 还会对非管理员隐藏吞吐（{@code channel_monitor_hide_throughput}）；
 * 本报表本来就不展示 RPM / TPM，天然不涉及这一项。</p>
 */
@Component
public class BotChannelStatusService {

    private static final Logger log = LoggerFactory.getLogger(BotChannelStatusService.class);

    /** 业务时区。 */
    private static final ZoneId ZONE = BusinessTime.ZONE;

    /** 允许的时间窗，与其它报表保持同一套写法。 */
    private static final Set<String> RANGES =
            Set.of("90m", "1h", "6h", "12h", "24h", "7d", "30d", "90d");

    /** 「渠道状态」看的是当下，默认近 1 小时（与 /号池 默认 24h 不同）。 */
    private static final String DEFAULT_RANGE = "1h";

    /** 明细最多展示多少个「平台 + 分组」。 */
    private static final int MAX_ROWS = 20;

    private static final String NO_DATABASE = """
            没有配置 Sub2API 只读库连接，渠道状态不可用。
            需要设置 SUB2API_DB_URL 与 SUB2API_DB_USERNAME（和号池增量采集共用同一套连接信息）。""";

    private final Sub2ChannelStatusReader reader;

    public BotChannelStatusService(Sub2ChannelStatusReader reader) {
        this.reader = reader;
    }

    /**
     * 渠道状态报表。
     *
     * @param admin        是否管理员（管理员不过滤分组）
     * @param viewerUserId 普通用户的 Sub2API 用户 ID；管理员传 null
     * @param range        时间窗，空则默认近 1 小时
     */
    public BotReport report(boolean admin, Long viewerUserId, String range) {
        if (!reader.isAvailable()) {
            return BotReport.fromMarkdown(NO_DATABASE);
        }
        String window = normalizeRange(range);
        Duration span = toDuration(window);
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime from = now.minus(span);

        List<ChannelStatusRow> rows;
        try {
            if (admin) {
                rows = reader.byPlatformGroup(from, now);
            } else {
                if (viewerUserId == null) {
                    return BotReport.fromMarkdown("查不到你的平台账号信息，无法确定可见的分组。");
                }
                List<Long> visible = reader.visibleGroupIds(viewerUserId);
                if (visible.isEmpty()) {
                    return BotReport.fromMarkdown("你当前没有可访问的分组，因此没有渠道状态可看。");
                }
                rows = reader.byPlatformGroup(from, now, visible);
            }
        } catch (RuntimeException ex) {
            log.warn("查询渠道状态失败: {}", ex.getMessage());
            return BotReport.fromMarkdown("查询渠道状态失败：" + rootMessage(ex)
                    + "\n如果提示权限不足，需要确认只读账号对 channel_monitor_v2_metrics_1m 与 groups 有 SELECT 权限。");
        }

        if (rows.isEmpty()) {
            return BotReport.fromMarkdown(admin
                    ? "近 " + window + " 没有渠道流量数据（或渠道监控 v2 未启用/尚未产生数据）。"
                    : "近 " + window + " 你有权限的分组没有流量。");
        }

        boolean truncated = rows.size() > MAX_ROWS;
        List<ChannelStatusRow> shown = truncated ? rows.subList(0, MAX_ROWS) : rows;

        long totalRequests = 0;
        long totalErrors = 0;
        List<BotReport.Row> detail = new ArrayList<>();
        for (ChannelStatusRow row : shown) {
            totalRequests += row.requestCount();
            totalErrors += row.errorRequests();
            detail.add(BotReport.Row.of(
                    safe(row.platform()),
                    groupLabel(row),
                    health(row),
                    String.valueOf(row.requestCount()),
                    percent(successRate(row)),
                    millis(row.averageTtftMs()),
                    percent(row.cacheRate())));
        }
        // 合计要覆盖全部行，不只是被截断后展示的那些
        if (truncated) {
            for (ChannelStatusRow row : rows.subList(MAX_ROWS, rows.size())) {
                totalRequests += row.requestCount();
                totalErrors += row.errorRequests();
            }
        }

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), List.of(
                BotReport.Row.of("时间窗", "近 " + window),
                BotReport.Row.of("分组数", String.valueOf(rows.size())),
                BotReport.Row.of("请求数", String.valueOf(totalRequests)),
                BotReport.Row.of("整体成功率", percent(totalRequests == 0
                        ? null
                        : (double) (totalRequests - totalErrors) / totalRequests))), null));

        blocks.add(new BotReport.Block("按平台 / 分组（近 " + window + "）", List.of(
                BotReport.Col.left("平台"),
                BotReport.Col.left("分组"),
                BotReport.Col.left("状态"),
                BotReport.Col.right("请求"),
                BotReport.Col.right("成功率"),
                BotReport.Col.right("首 TOKEN"),
                BotReport.Col.right("缓存率")), detail, null));

        List<String> notes = new ArrayList<>();
        notes.add("缓存率 = 缓存读取 /（输入 + 缓存写入 + 缓存读取），与 Sub2API 渠道监控口径一致（分母不含输出）。");
        notes.add("状态按错误率分档：<5% 正常，5%~20% 注意，≥20% 异常；该档位是本系统固定规则，非上游可配置阈值。");
        if (!admin) {
            notes.add("范围：只统计你有权限的分组。");
        }
        if (truncated) {
            notes.add("共有 " + rows.size() + " 个分组有流量，这里只列出请求数最高的 " + MAX_ROWS + " 个（合计仍是全量）。");
        }
        return BotReport.of("渠道状态（近 " + window + "）", blocks, notes);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private static String health(ChannelStatusRow row) {
        long requests = row.requestCount();
        if (requests <= 0) {
            return "无流量";
        }
        double errorRate = (double) row.errorRequests() / requests;
        if (errorRate >= 0.20) {
            return "异常";
        }
        return errorRate >= 0.05 ? "注意" : "正常";
    }

    private static Double successRate(ChannelStatusRow row) {
        long requests = row.requestCount();
        return requests <= 0 ? null : (double) (requests - row.errorRequests()) / requests;
    }

    private static String groupLabel(ChannelStatusRow row) {
        if (row.groupId() <= 0) {
            return "未分组";
        }
        return row.groupName() == null || row.groupName().isBlank()
                ? ("分组 " + row.groupId())
                : row.groupName();
    }

    private static String normalizeRange(String range) {
        if (range == null || range.isBlank()) {
            return DEFAULT_RANGE;
        }
        String normalized = range.trim().toLowerCase(Locale.ROOT);
        return RANGES.contains(normalized) ? normalized : DEFAULT_RANGE;
    }

    private static Duration toDuration(String window) {
        char unit = window.charAt(window.length() - 1);
        long amount = Long.parseLong(window.substring(0, window.length() - 1));
        return switch (unit) {
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> Duration.ofHours(1);
        };
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** 首 Token 用秒更好读（与页面上 6.4s 的写法一致）。 */
    private static String millis(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1fs", value / 1000.0);
    }
}
