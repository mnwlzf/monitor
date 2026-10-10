package com.monitor.platform.bot.user;

import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 用户端数据查询：把「发送者邮箱」翻译成「他自己的 Sub2API 数据」。
 *
 * <p>取数链路：邮箱 → 用户 ID → 用平台管理员密钥调 Sub2API 管理端接口。
 * 用户 ID 优先取 Redis 缓存（同步任务写入），缓存没有时回落到按邮箱搜索接口，
 * <strong>绝不接受调用方传入的 ID</strong>，保证「只能查自己」。</p>
 *
 * <p>任何一步失败（没配号池源、没配管理员密钥、上游报错、查不到账号）都返回
 * 一句可读的提示，不抛异常 —— 用户端不该看到堆栈式报错。</p>
 */
@Service
public class BotUserService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 密钥列表单次最多拉取的条数。 */
    private static final int API_KEY_PAGE_SIZE = 50;

    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final Sub2ApiUserCache userCache;
    private final Sub2UserAdminClient client;

    public BotUserService(PlatformRepository platformRepository,
                          PoolAdminKeyResolver adminKeyResolver,
                          Sub2ApiUserCache userCache,
                          Sub2UserAdminClient client) {
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.userCache = userCache;
        this.client = client;
    }

    // ============================================================ 对外报表

    /** 我的余额。 */
    public BotReport balanceReport(String email) {
        Conn conn = connect(email);
        if (conn.error() != null) {
            return message(conn.error());
        }
        Optional<Sub2AdminUser> found = client.findUserByEmail(conn.baseUrl(), conn.adminKey(), email);
        if (found.isEmpty()) {
            return message("在平台上没有找到你的账号，请联系管理员。");
        }
        Sub2AdminUser u = found.get();

        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("邮箱", nz(u.email())));
        rows.add(BotReport.Row.of("余额", money(u.balance())));
        rows.add(BotReport.Row.of("冻结", money(u.frozenBalance())));
        rows.add(BotReport.Row.of("累计充值", money(u.totalRecharged())));
        rows.add(BotReport.Row.of("状态", statusText(u.status())));
        if (u.concurrency() != null) {
            rows.add(BotReport.Row.of("并发上限", String.valueOf(u.concurrency())));
        }
        rows.add(BotReport.Row.of("最近活跃",
                u.lastActiveAt() == null ? "—" : u.lastActiveAt().atZoneSameInstant(ZONE).format(TIME)));

        BotReport.Block table = new BotReport.Block(null,
                List.of(BotReport.Col.left("项目"), BotReport.Col.right("值")), rows, null);
        return BotReport.of("我的余额", List.of(table), List.of());
    }

    /** 我的用量。{@code range} 支持 today / week / month（以及中文别名）。 */
    public BotReport usageReport(String email, String range) {
        Conn conn = connect(email);
        if (conn.error() != null) {
            return message(conn.error());
        }
        Long userId = userId(conn, email);
        if (userId == null) {
            return message("在平台上没有找到你的账号，请联系管理员。");
        }
        String period = normalizePeriod(range);
        Sub2AdminUsageStats stats = client.fetchUsageStats(conn.baseUrl(), conn.adminKey(), userId, period);

        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("请求数", count(stats.totalRequests())));
        rows.add(BotReport.Row.of("输入 Token", count(stats.totalInputTokens())));
        rows.add(BotReport.Row.of("输出 Token", count(stats.totalOutputTokens())));
        rows.add(BotReport.Row.of("缓存读取 Token", count(stats.totalCacheReadTokens())));
        rows.add(BotReport.Row.of("缓存写入 Token", count(stats.totalCacheCreationTokens())));
        rows.add(BotReport.Row.of("总 Token", count(stats.totalTokens())));
        rows.add(BotReport.Row.of("实际消耗", money(stats.totalActualCost())));
        rows.add(BotReport.Row.of("标准计费", money(stats.totalCost())));
        if (stats.averageDurationMs() > 0) {
            rows.add(BotReport.Row.of("平均耗时", Math.round(stats.averageDurationMs()) + " ms"));
        }

        BotReport.Block table = new BotReport.Block(null,
                List.of(BotReport.Col.left("指标"), BotReport.Col.right("数值")), rows, null);
        return BotReport.of("我的用量 · " + periodLabel(period), List.of(table), List.of());
    }

    /** 我的密钥。 */
    public BotReport apiKeysReport(String email) {
        Conn conn = connect(email);
        if (conn.error() != null) {
            return message(conn.error());
        }
        Long userId = userId(conn, email);
        if (userId == null) {
            return message("在平台上没有找到你的账号，请联系管理员。");
        }
        List<Sub2AdminApiKey> keys = client.fetchApiKeys(
                conn.baseUrl(), conn.adminKey(), userId, 1, API_KEY_PAGE_SIZE);
        if (keys.isEmpty()) {
            return message("你名下还没有 API Key。");
        }

        List<BotReport.Row> rows = new ArrayList<>();
        for (Sub2AdminApiKey key : keys) {
            rows.add(BotReport.Row.of(
                    nz(key.name()),
                    key.maskedKey(),
                    statusText(key.status()),
                    quotaText(key),
                    key.lastUsedAt() == null ? "从未" : key.lastUsedAt().atZoneSameInstant(ZONE).format(TIME)));
        }
        BotReport.Block table = new BotReport.Block(null, List.of(
                BotReport.Col.left("名称"),
                BotReport.Col.left("密钥"),
                BotReport.Col.left("状态"),
                BotReport.Col.right("额度"),
                BotReport.Col.left("最近使用")), rows, null);
        return BotReport.of("我的密钥（共 " + keys.size() + " 个）", List.of(table), List.of());
    }

    // ============================================================ 解析

    /** 解析平台连接信息（地址 + 管理员密钥）。 */
    private Conn connect(String email) {
        if (email == null || email.isBlank()) {
            return Conn.error("没有识别到你的邮箱，先发送 /绑定 <邮箱> 绑定。");
        }
        PlatformEntity platform = poolPlatform();
        if (platform == null) {
            return Conn.error("平台还没配置好（未标记号池监控源），请联系管理员。");
        }
        String adminKey = adminKeyResolver.resolve(platform).orElse(null);
        if (adminKey == null) {
            return Conn.error("平台管理员密钥未配置，请联系管理员。");
        }
        return new Conn(platform.getUrl(), adminKey, null);
    }

    /** 邮箱对应的用户 ID：先查缓存，缓存没有就回落到按邮箱搜索。 */
    private Long userId(Conn conn, String email) {
        Long cached = userCache.userId(email).orElse(null);
        if (cached != null) {
            return cached;
        }
        return client.findUserByEmail(conn.baseUrl(), conn.adminKey(), email)
                .map(Sub2AdminUser::id)
                .orElse(null);
    }

    /** 找到被标记为「号池监控源」的平台；没有就返回 null。 */
    private PlatformEntity poolPlatform() {
        return platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private record Conn(String baseUrl, String adminKey, String error) {
        static Conn error(String message) {
            return new Conn(null, null, message);
        }
    }

    // ============================================================ 格式化

    private static BotReport message(String text) {
        return BotReport.fromMarkdown(text);
    }

    /** 把用户输入的时间窗归一成 Sub2API 认的三个取值。 */
    static String normalizePeriod(String range) {
        if (range == null) {
            return "today";
        }
        return switch (range.trim().toLowerCase(Locale.ROOT)) {
            case "week", "7d", "7天", "周", "近7天", "本周" -> "week";
            case "month", "30d", "30天", "月", "近30天", "本月" -> "month";
            default -> "today";
        };
    }

    private static String periodLabel(String period) {
        return switch (period) {
            case "week" -> "近 7 天";
            case "month" -> "近 30 天";
            default -> "今日";
        };
    }

    private static String quotaText(Sub2AdminApiKey key) {
        double quota = key.quota() == null ? 0d : key.quota();
        double used = key.quotaUsed() == null ? 0d : key.quotaUsed();
        if (quota <= 0) {
            return "不限（已用 " + money(used) + "）";
        }
        return money(used) + " / " + money(quota);
    }

    private static String statusText(String status) {
        if (status == null || status.isBlank()) {
            return "—";
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "active" -> "正常";
            case "disabled", "banned" -> "已停用";
            case "expired" -> "已过期";
            case "exhausted" -> "额度用尽";
            default -> status;
        };
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String money(Double value) {
        if (value == null) {
            return "0";
        }
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return decimal.toPlainString();
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}