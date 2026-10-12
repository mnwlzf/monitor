package com.monitor.platform.bot.pool;

import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.ReportFormats;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolQueryService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 号池相关的聊天报表：汇总矩阵、热力图标记、直连库增量采集状态。
 *
 * <p>从 {@code MonitorChatTools} 抽出：列、配色、时间桶合并与热力值加权这些结构细节
 * 集中在这里，{@code MonitorChatTools} 只保留工具入口与其余域报表。</p>
 */
@Component
public class PoolReportService {

    /** 允许的时间窗，避免模型或用户传入奇怪的值。 */
    private static final Set<String> RANGES = Set.of("90m", "1h", "6h", "12h", "24h", "7d", "30d", "90d");

    /** 热力图最多展示的行数（有流量的账号，按请求数倒序）。 */
    private static final int MAX_HEAT_ROWS = 20;

    /** 热力图最多展示的列数；超过就按相邻桶合并，避免图片过宽。 */
    private static final int MAX_HEAT_COLS = 32;

    private static final DateTimeFormatter BUCKET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter BUCKET_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final PlatformRepository platformRepository;
    private final PoolQueryService poolQueryService;

    public PoolReportService(PlatformRepository platformRepository, PoolQueryService poolQueryService) {
        this.platformRepository = platformRepository;
        this.poolQueryService = poolQueryService;
    }

    /** 生成「热力图标记 + 关键数字」，供大模型引用。 */
    public String heatmapDirective(String range) {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return "还没有把任何平台标记为「号池监控源」，无法查询号池数据。";
        }
        String window = normalizeRange(range);
        PoolHeatmapResponse heatmap = poolQueryService.heatmap(platformId, window, null, null, null, null);
        List<PoolHeatmapResponse.Row> active = heatmap.rows().stream()
                .filter(row -> row.total() != null && row.total().requests() > 0)
                .sorted(Comparator.comparingLong((PoolHeatmapResponse.Row row) -> row.total().requests()).reversed())
                .toList();
        if (active.isEmpty()) {
            return "近 " + window + " 号池没有任何流量。";
        }

        long totalRequests = active.stream().mapToLong(row -> row.total().requests()).sum();
        PoolHeatmapResponse.Row best = active.stream()
                .filter(row -> row.total().cacheHitRate() != null)
                .max(Comparator.comparingDouble(row -> row.total().cacheHitRate()))
                .orElse(null);
        PoolHeatmapResponse.Row worst = active.stream()
                .filter(row -> row.total().cacheHitRate() != null)
                .min(Comparator.comparingDouble(row -> row.total().cacheHitRate()))
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("[[HEATMAP:").append(window).append("]]\n");
        sb.append("（系统会在标记处渲染出近 ").append(window)
                .append(" 的号池热力图，请勿自己画表格或色块）\n");
        sb.append("关键数字：有流量渠道 ").append(active.size())
                .append(" 个，请求合计 ").append(totalRequests);
        if (best != null) {
            sb.append("；缓存率最高 ").append(ReportFormats.percent(best.total().cacheHitRate()))
                    .append("（").append(label(best)).append("）");
        }
        if (worst != null) {
            sb.append("，最低 ").append(ReportFormats.percent(worst.total().cacheHitRate()))
                    .append("（").append(label(worst)).append("）");
        }
        sb.append("。");
        return sb.toString();
    }

    /**
     * 号池监控：左侧是账号汇总，右侧是时间桶热力色块。
     *
     * <p>列与配色都对齐前端「号池 → 趋势矩阵」页，这样 QQ 里看到的和页面上是同一张表。</p>
     */
    public BotReport report(String range) {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return BotReport.fromMarkdown("还没有把任何平台标记为「号池监控源」，无法查询号池数据。");
        }
        String window = normalizeRange(range);
        PoolHeatmapResponse heatmap = poolQueryService.heatmap(platformId, window, null, null, null, null);
        if (heatmap.rows().isEmpty()) {
            return BotReport.fromMarkdown("号池里还没有账号（或尚未采集过）。");
        }

        List<PoolHeatmapResponse.Row> active = heatmap.rows().stream()
                .filter(row -> row.total() != null && row.total().requests() > 0)
                .sorted(Comparator.comparingLong((PoolHeatmapResponse.Row row) -> row.total().requests()).reversed())
                .toList();
        List<PoolHeatmapResponse.Row> idle = heatmap.rows().stream()
                .filter(row -> row.total() == null || row.total().requests() == 0)
                .toList();

        List<BotReport.Block> blocks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!active.isEmpty()) {
            Buckets buckets = buckets(heatmap);
            List<BotReport.Col> cols = new ArrayList<>(List.of(
                    BotReport.Col.left("平台 / 账号"),
                    BotReport.Col.right("缓存率"),
                    BotReport.Col.right("首 TOKEN"),
                    BotReport.Col.right("每秒 TOKEN"),
                    BotReport.Col.right("请求")));
            for (String label : buckets.labels()) {
                cols.add(BotReport.Col.heat(label));
            }

            List<BotReport.Row> rows = new ArrayList<>();
            int limit = Math.min(active.size(), MAX_HEAT_ROWS);
            for (int i = 0; i < limit; i++) {
                PoolHeatmapResponse.Row row = active.get(i);
                List<BotReport.Cell> cells = new ArrayList<>(List.of(
                        BotReport.Cell.of(label(row)),
                        BotReport.Cell.of(ReportFormats.percent(row.total().cacheHitRate())),
                        BotReport.Cell.of(ReportFormats.seconds(row.total().avgFirstTokenMs())),
                        BotReport.Cell.of(ReportFormats.tps(row.total().tokensPerSecond())),
                        BotReport.Cell.of(String.valueOf(row.total().requests()))));
                for (Double heat : buckets.heatOf(row)) {
                    cells.add(BotReport.Cell.heat(heat));
                }
                rows.add(new BotReport.Row(cells));
            }

            blocks.add(new BotReport.Block("一、有流量的渠道（近 " + window + "，按请求数排序）",
                    cols, rows, null));
            if (active.size() > limit) {
                notes.add("有流量的渠道共 " + active.size() + " 个，这里只列出请求数最高的 " + limit + " 个。");
            }
        } else {
            notes.add("近 " + window + " 号池没有任何流量。");
        }

        if (!idle.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (PoolHeatmapResponse.Row row : idle) {
                names.add(label(row));
            }
            blocks.add(BotReport.paragraph("二、无流量的渠道（共 " + idle.size() + " 个）",
                    List.of(String.join("、", names))));
        }

        long totalRequests = active.stream()
                .mapToLong(row -> row.total().requests())
                .sum();
        notes.add("合计：有流量渠道 " + active.size() + " 个，请求 " + totalRequests
                + "；无流量渠道 " + idle.size() + " 个。");
        notes.add("色块为该时段缓存命中率：越绿越高、越红越低，灰色表示该时段没有流量。");

        return BotReport.of("号池监控（近 " + window + "）", blocks, notes);
    }

    /** 号池直连库增量采集状态。 */
    public BotReport ingestReport() {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return BotReport.fromMarkdown("还没有把任何平台标记为「号池监控源」。");
        }
        PoolIngestStatusResponse status = poolQueryService.ingestStatus(platformId);
        if (!status.enabled()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集未启用（SUB2API_DB_ENABLED=false），分钟级数据依赖 10 分钟一轮的接口采集。");
        }
        if (!status.configured()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集已开启，但数据源没配全（缺 URL 或用户名），任务每轮跳过。");
        }
        if (!status.passwordConfigured()) {
            return BotReport.fromMarkdown(
                    "号池直连库增量采集已开启，但没配密码；若上游要求密码认证，连接会失败、游标不会推进。");
        }
        if (status.lastUsageLogId() <= 0L) {
            return BotReport.fromMarkdown("号池直连库增量采集已就绪，但游标尚未初始化（还没成功拉取过）。");
        }

        List<BotReport.Row> rows = List.of(
                BotReport.Row.of("游标 usage_logs.id", String.valueOf(status.lastUsageLogId())),
                BotReport.Row.of("游标最后推进", ReportFormats.time(status.lastRunAt())),
                BotReport.Row.of("最新明细", ReportFormats.time(status.latestSampleAt())),
                BotReport.Row.of("数据滞后", status.lagSeconds() == null ? "-" : ReportFormats.humanDuration(status.lagSeconds())));

        BotReport.Block table = new BotReport.Block(null, List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), rows, null);

        return BotReport.of("号池直连库增量采集：正常", List.of(table), List.of());
    }

    /**
     * 热力图列。
     *
     * <p>保留全部时间桶（只在超过 {@link #MAX_HEAT_COLS} 时才合并相邻桶），
     * 但<strong>不逐列打标签</strong> —— 前端也是每隔几列标一次，
     * 逐列标会把列撑得很宽、热力块反而变小。</p>
     */
    private Buckets buckets(PoolHeatmapResponse heatmap) {
        List<OffsetDateTime> source = heatmap.buckets();
        boolean day = "day".equalsIgnoreCase(heatmap.granularity());
        int step = Math.max(1, (int) Math.ceil(source.size() / (double) MAX_HEAT_COLS));

        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < source.size(); i += step) {
            List<Integer> group = new ArrayList<>();
            for (int j = i; j < Math.min(i + step, source.size()); j++) {
                group.add(j);
            }
            groups.add(group);
        }

        // 大约每 8 列标一次时间，其余列留空表头
        int labelStep = Math.max(1, groups.size() / 8);
        List<String> labels = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            if (i % labelStep != 0) {
                labels.add("");
                continue;
            }
            int bucketIndex = groups.get(i).get(0);
            labels.add((day ? BUCKET_DAY : BUCKET_TIME).format(source.get(bucketIndex)));
        }
        return new Buckets(labels, groups);
    }

    private String label(PoolHeatmapResponse.Row row) {
        String name = row.name() == null || row.name().isBlank() ? ("账号 " + row.externalAccountId()) : row.name();
        String platform = row.platform() == null || row.platform().isBlank() ? "未知平台" : row.platform();
        return platform + " / " + name;
    }

    /** 找到被标记为「号池监控源」的平台；没有就返回 null。 */
    private Integer poolPlatformId() {
        return platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .map(PlatformEntity::getId)
                .findFirst()
                .orElse(null);
    }

    private String normalizeRange(String range) {
        if (range == null) {
            return "24h";
        }
        String normalized = range.trim().toLowerCase(Locale.ROOT);
        return RANGES.contains(normalized) ? normalized : "24h";
    }

    /**
     * 热力图列的分组信息。
     *
     * @param labels 列标题（合并后的时间桶）
     * @param groups 每列对应的原始桶下标
     */
    private record Buckets(List<String> labels, List<List<Integer>> groups) {

        /** 取某一行在每一列的热力值：组内按请求数加权平均缓存率，全无流量时为 null。 */
        List<Double> heatOf(PoolHeatmapResponse.Row row) {
            List<Double> result = new ArrayList<>(groups.size());
            for (List<Integer> group : groups) {
                double weighted = 0;
                long requests = 0;
                for (int index : group) {
                    if (index >= row.cells().size()) {
                        continue;
                    }
                    PoolHeatmapResponse.Metrics cell = row.cells().get(index);
                    if (cell == null || cell.requests() <= 0 || cell.cacheHitRate() == null) {
                        continue;
                    }
                    weighted += cell.cacheHitRate() * cell.requests();
                    requests += cell.requests();
                }
                result.add(requests == 0 ? null : weighted / requests);
            }
            return result;
        }
    }
}