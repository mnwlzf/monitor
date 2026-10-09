package com.monitor.platform.bot;

import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.PoolAccountResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.service.UpstreamAdminService;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 对话可查询的数据入口。
 *
 * <p>同一份实现同时服务两条链路：</p>
 * <ul>
 *     <li>Spring AI 的 function calling：方法上的 {@link Tool} 描述给模型看，模型自己决定调哪个；</li>
 *     <li>确定性命令（{@code /平台}、{@code /号池} 等）：直接调用同样的方法，没配大模型也能用。</li>
 * </ul>
 *
 * <p>全部是只读查询，绝不触发采集、删除等写操作。</p>
 */
@Component
public class MonitorChatTools {

    /** 允许的时间窗，避免模型或用户传入奇怪的值。 */
    private static final Set<String> RANGES = Set.of("90m", "1h", "6h", "12h", "24h", "7d", "30d", "90d");

    private final PlatformRepository platformRepository;
    private final UpstreamAdminService upstreamAdminService;
    private final PoolQueryService poolQueryService;

    public MonitorChatTools(PlatformRepository platformRepository,
                            UpstreamAdminService upstreamAdminService,
                            PoolQueryService poolQueryService) {
        this.platformRepository = platformRepository;
        this.upstreamAdminService = upstreamAdminService;
        this.poolQueryService = poolQueryService;
    }

    @Tool(description = "查询所有上游平台的概览：平台名、类型、账号数、异常账号数与余额合计")
    public String platformOverview() {
        List<PlatformResponse> platforms = upstreamAdminService.listPlatforms();
        if (platforms.isEmpty()) {
            return "当前还没有接入任何上游平台。";
        }
        StringBuilder sb = new StringBuilder("上游平台概览：");
        for (PlatformResponse platform : platforms) {
            List<AccountResponse> accounts = upstreamAdminService.listAccounts(platform.id());
            long abnormal = accounts.stream().filter(a -> !Boolean.TRUE.equals(a.status())).count();
            BigDecimal balance = accounts.stream()
                    .map(AccountResponse::balance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append("\n- ").append(platform.name())
                    .append("（").append(platform.platform()).append("）")
                    .append("：账号 ").append(accounts.size())
                    .append("，异常 ").append(abnormal)
                    .append("，余额合计 ").append(balance.stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    @Tool(description = "查询号池账号在指定时间窗内的请求数、缓存命中率与首 Token 耗时。"
            + "range 取值 90m / 1h / 6h / 12h / 24h / 7d / 30d / 90d，默认 24h")
    public String poolOverview(String range) {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return "还没有把任何平台标记为「号池监控源」，无法查询号池数据。";
        }
        String window = normalizeRange(range);
        List<PoolAccountResponse> accounts = poolQueryService.listAccounts(platformId, window);
        if (accounts.isEmpty()) {
            return "号池里还没有账号（或尚未采集过）。";
        }
        List<PoolAccountResponse> sorted = accounts.stream()
                .sorted(Comparator.comparingLong((PoolAccountResponse a) -> num(a.requests())).reversed())
                .toList();

        long totalRequests = 0L;
        long input = 0L;
        long read = 0L;
        long creation = 0L;
        StringBuilder sb = new StringBuilder("号池监控（近 ").append(window).append("）：");
        for (PoolAccountResponse account : sorted) {
            totalRequests += num(account.requests());
            input += num(account.inputTokens());
            read += num(account.cacheReadTokens());
            creation += num(account.cacheCreationTokens());
            sb.append("\n- ").append(displayName(account))
                    .append(" [").append(account.platform() == null ? "未知平台" : account.platform()).append("]")
                    .append("：请求 ").append(num(account.requests()))
                    .append("，缓存率 ").append(percent(account.cacheHitRate()))
                    .append("，首 Token ").append(millis(account.avgFirstTokenMs()));
        }
        long denominator = input + read + creation;
        sb.append("\n合计：请求 ").append(totalRequests)
                .append("，整体缓存率 ").append(percent(denominator > 0 ? (double) read / denominator : null));
        return sb.toString();
    }

    @Tool(description = "查询号池直连库增量采集的状态：是否生效、游标位置、最新明细滞后多少秒")
    public String poolIngestStatus() {
        Integer platformId = poolPlatformId();
        if (platformId == null) {
            return "还没有把任何平台标记为「号池监控源」。";
        }
        PoolIngestStatusResponse status = poolQueryService.ingestStatus(platformId);
        if (!status.enabled()) {
            return "号池直连库增量采集未启用（SUB2API_DB_ENABLED=false），分钟级数据依赖 10 分钟一轮的接口采集。";
        }
        if (!status.configured()) {
            return "号池直连库增量采集已开启，但数据源没配全（缺 URL 或用户名），任务每轮跳过。";
        }
        if (!status.passwordConfigured()) {
            return "号池直连库增量采集已开启，但没配密码；若上游要求密码认证，连接会失败、游标不会推进。";
        }
        if (status.lastUsageLogId() <= 0L) {
            return "号池直连库增量采集已就绪，但游标尚未初始化（还没成功拉取过）。";
        }
        StringBuilder sb = new StringBuilder("号池直连库增量采集正常：");
        sb.append("\n- 游标 usage_logs.id：").append(status.lastUsageLogId());
        sb.append("\n- 游标最后推进：").append(time(status.lastRunAt()));
        sb.append("\n- 最新明细：").append(time(status.latestSampleAt()));
        if (status.lagSeconds() != null) {
            sb.append("\n- 数据滞后：").append(humanDuration(status.lagSeconds()));
        }
        return sb.toString();
    }

    @Tool(description = "按名称关键字模糊查询账号的余额、额度与最近采集状态")
    public String accountDetail(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "请提供账号名称关键字，例如：/账号 主账号";
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            for (AccountResponse account : upstreamAdminService.listAccounts(platform.id())) {
                String name = account.displayName() == null ? "" : account.displayName();
                String login = account.loginName() == null ? "" : account.loginName();
                if (!name.toLowerCase(Locale.ROOT).contains(needle)
                        && !login.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                matched++;
                sb.append("\n- ").append(name.isBlank() ? ("账号 " + account.id()) : name)
                        .append("（").append(platform.name()).append("）")
                        .append("：状态 ").append(Boolean.TRUE.equals(account.status()) ? "正常" : "异常")
                        .append("，余额 ").append(account.balance() == null ? "-" : account.balance().stripTrailingZeros().toPlainString())
                        .append("，已用额度 ").append(account.usedQuota() == null ? "-" : account.usedQuota().stripTrailingZeros().toPlainString())
                        .append("，最近采集 ").append(time(account.lastCollectedAt()))
                        .append(account.lastCollectStatus() == null ? "" : ("（" + account.lastCollectStatus() + "）"));
            }
        }
        if (matched == 0) {
            return "没有找到名称包含「" + keyword.trim() + "」的账号。";
        }
        return "匹配到 " + matched + " 个账号：" + sb;
    }

    @Tool(description = "查询最近的账号与密钥变更记录，limit 为返回条数，默认 5")
    public String recentChanges(int limit) {
        int size = limit <= 0 ? 5 : Math.min(limit, 20);
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (PlatformResponse platform : upstreamAdminService.listPlatforms()) {
            for (UpstreamChangeEventResponse event : upstreamAdminService.listChanges(platform.id(), size)) {
                if (matched >= size) {
                    break;
                }
                matched++;
                sb.append("\n- ").append(time(event.detectedAt()))
                        .append(" [").append(platform.name()).append("] ")
                        .append(event.message() == null ? (event.changeType() + " " + event.fieldName()) : event.message());
            }
        }
        return matched == 0 ? "最近没有任何变更记录。" : ("最近 " + matched + " 条变更：" + sb);
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

    private String displayName(PoolAccountResponse account) {
        return account.name() == null || account.name().isBlank()
                ? "账号 " + account.externalAccountId()
                : account.name();
    }

    private static long num(Long value) {
        return value == null ? 0L : value;
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String millis(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.0fms", value);
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "-" : value.toString().replace('T', ' ').substring(0, 16);
    }

    private static String humanDuration(long seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0L, seconds));
        if (duration.toMinutes() < 1) {
            return seconds + " 秒";
        }
        if (duration.toHours() < 1) {
            return duration.toMinutes() + " 分钟";
        }
        if (duration.toDays() < 1) {
            return duration.toHours() + " 小时";
        }
        return duration.toDays() + " 天";
    }
}