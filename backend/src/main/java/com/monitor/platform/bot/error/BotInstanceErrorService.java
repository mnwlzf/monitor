package com.monitor.platform.bot.error;

import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 多 Sub2API 实例错误概览。
 *
 * <p>通过每个平台已保存的管理员密钥调用 Ops 概览接口，不要求额外配置多个只读数据库。</p>
 */
@Service
public class BotInstanceErrorService {

    private static final Logger log = LoggerFactory.getLogger(BotInstanceErrorService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final Set<String> RANGES = Set.of(
            "5m", "15m", "30m", "90m", "1h", "6h", "12h", "24h", "7d", "30d");

    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final Sub2OpsAdminClient client;

    public BotInstanceErrorService(PlatformRepository platformRepository,
                                   PoolAdminKeyResolver adminKeyResolver,
                                   Sub2OpsAdminClient client) {
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.client = client;
    }

    public BotReport report(String arg) {
        Window window = window(arg);
        List<PlatformEntity> platforms = platformRepository.findAll().stream()
                .filter(item -> item.getDeletedAt() == null)
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .filter(item -> item.getPlatformType() != null
                        && item.getPlatformType().toLowerCase(Locale.ROOT).contains("sub2"))
                .filter(PlatformEntity::hasAdminKey)
                .toList();
        if (platforms.isEmpty()) {
            return BotReport.fromMarkdown("没有找到配置了管理员密钥的 Sub2API 实例。");
        }

        List<InstanceResult> results = new ArrayList<>();
        for (PlatformEntity platform : platforms) {
            Optional<String> adminKey = adminKeyResolver.resolve(platform);
            if (adminKey.isEmpty()) {
                results.add(InstanceResult.failed(platform, "管理员密钥无法解密"));
                continue;
            }
            try {
                Sub2OpsOverview overview = client.fetchOverview(
                        platform.getUrl(), adminKey.get(), window.from(), window.to());
                results.add(InstanceResult.ok(platform, overview));
            } catch (RuntimeException ex) {
                log.warn("查询实例错误概览失败: platformId={}, reason={}",
                        platform.getId(), ex.getMessage());
                results.add(InstanceResult.failed(platform, rootMessage(ex)));
            }
        }
        results.sort((left, right) -> {
            if (left.overview() == null && right.overview() == null) {
                return 0;
            }
            if (left.overview() == null) {
                return 1;
            }
            if (right.overview() == null) {
                return -1;
            }
            return Double.compare(right.overview().errorRate(), left.overview().errorRate());
        });
        return report(window, results);
    }

    private BotReport report(Window window, List<InstanceResult> results) {
        long totalRequests = 0;
        long totalErrors = 0;
        long totalSlaRequests = 0;
        long totalSlaErrors = 0;
        InstanceResult worst = null;
        List<BotReport.Row> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (InstanceResult result : results) {
            PlatformEntity platform = result.platform();
            String name = platform.getPlatformName() == null || platform.getPlatformName().isBlank()
                    ? platform.getUrl() : platform.getPlatformName();
            if (result.overview() == null) {
                rows.add(BotReport.Row.of(name, "-", "-", "-", "-", "-", "-", "查询失败"));
                notes.add(name + "：" + result.error());
                continue;
            }
            Sub2OpsOverview overview = result.overview();
            totalRequests += overview.requestCountTotal();
            totalErrors += overview.errorCountTotal();
            long excluded = Math.max(0, overview.errorCountTotal() - overview.errorCountSla());
            totalSlaRequests += Math.max(0, overview.requestCountTotal() - excluded);
            totalSlaErrors += overview.errorCountSla();
            if (worst == null || overview.errorRate() > worst.overview().errorRate()) {
                worst = result;
            }
            rows.add(BotReport.Row.of(
                    name,
                    count(overview.requestCountTotal()),
                    count(overview.errorCountTotal()),
                    percent(overview.errorRate()),
                    count(overview.upstream429Count() + overview.upstream529Count()),
                    count(overview.upstreamErrorCountExcl429529()),
                    String.valueOf(healthScore(overview)),
                    health(overview)));
        }

        Double overallRate = totalSlaRequests <= 0 ? null : (double) totalSlaErrors / totalSlaRequests;
        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block("概览", List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), List.of(
                BotReport.Row.of("时间窗", window.label()),
                BotReport.Row.of("实例数", String.valueOf(results.size())),
                BotReport.Row.of("请求数", count(totalRequests)),
                BotReport.Row.of("错误数", count(totalErrors)),
                BotReport.Row.of("整体错误率", percent(overallRate)),
                BotReport.Row.of("最差实例", worst == null ? "-" : instanceName(worst.platform()))), null));
        blocks.add(new BotReport.Block("实例明细", List.of(
                BotReport.Col.left("实例"),
                BotReport.Col.right("请求"),
                BotReport.Col.right("错误"),
                BotReport.Col.right("错误率"),
                BotReport.Col.right("429/529"),
                BotReport.Col.right("上游错误"),
                BotReport.Col.right("健康分"),
                BotReport.Col.left("状态")), rows, null));
        notes.add("多实例概览通过各实例的 Sub2API Ops 接口读取，不依赖额外数据库连接。");
        notes.add("错误率采用 Sub2API SLA 口径，排除业务限制类错误；健康分缺失时按错误率、上游错误率和 429/529 占比估算。");
        notes.add("状态按错误率分档：<5% 正常，5%~20% 注意，≥20% 异常。");
        return BotReport.of("多实例错误诊断（" + window.label() + "）", blocks, notes);
    }

    private static String instanceName(PlatformEntity platform) {
        return platform.getPlatformName() == null || platform.getPlatformName().isBlank()
                ? platform.getUrl() : platform.getPlatformName();
    }

    private static String health(Sub2OpsOverview overview) {
        int score = healthScore(overview);
        if (score < 50) {
            return "异常";
        }
        if (score < 80) {
            return "注意";
        }
        return "正常";
    }

    /**
     * Sub2API 旧版本没有 health_score 字段；缺失时按错误率、上游错误率和 429/529 占比本地估算。
     */
    private static int healthScore(Sub2OpsOverview overview) {
        if (overview.healthScore() >= 0) {
            return overview.healthScore();
        }
        double rateLimitedRatio = overview.requestCountTotal() <= 0 ? 0
                : (double) (overview.upstream429Count() + overview.upstream529Count())
                / overview.requestCountTotal();
        double penalty = overview.errorRate() * 400
                + overview.upstreamErrorRate() * 100
                + rateLimitedRatio * 50;
        return (int) Math.max(0, Math.min(100, Math.round(100 - penalty)));
    }

    private static Window window(String arg) {
        String value = arg == null || arg.isBlank() ? "1h" : arg.trim().toLowerCase(Locale.ROOT);
        if (!RANGES.contains(value)) {
            value = "1h";
        }
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        Duration duration = switch (value.charAt(value.length() - 1)) {
            case 'm' -> Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            case 'h' -> Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            case 'd' -> Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)));
            default -> Duration.ofHours(1);
        };
        return new Window(now.minus(duration), now, "近 " + value);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record Window(OffsetDateTime from, OffsetDateTime to, String label) {
    }

    private record InstanceResult(PlatformEntity platform, Sub2OpsOverview overview, String error) {
        static InstanceResult ok(PlatformEntity platform, Sub2OpsOverview overview) {
            return new InstanceResult(platform, overview, null);
        }

        static InstanceResult failed(PlatformEntity platform, String error) {
            return new InstanceResult(platform, null, error);
        }
    }
}
