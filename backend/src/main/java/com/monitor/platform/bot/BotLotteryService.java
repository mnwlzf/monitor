package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.usage.Sub2UserUsageReader;
import com.monitor.platform.bot.user.Sub2AdminUser;
import com.monitor.platform.bot.user.Sub2UserAdminClient;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QQ 机器人每日消费抽奖。
 *
 * <p>资格按抽奖当天之前一个完整自然日计算。例如 2026-10-28 任意时刻抽奖，
 * 统计的是 2026-10-27 00:00 至 2026-10-28 00:00（Asia/Shanghai）的
 * {@code actual_cost}。</p>
 */
@Service
public class BotLotteryService {

    private static final Logger log = LoggerFactory.getLogger(BotLotteryService.class);
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String CHANNEL = BotEmailBindingService.CHANNEL_QQ;
    private static final String DRAW_LOCK_KEY = "monitor:bot:lottery:draw:lock";
    private static final Duration DRAW_LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration PENDING_BUDGET_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BotLotterySettingsService settingsService;
    private final BotLotteryDrawRepository drawRepository;
    private final BotEmailBindingService bindingService;
    private final Sub2ApiUserCache userCache;
    private final Sub2UserUsageReader usageReader;
    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final Sub2UserAdminClient adminClient;
    private final StringRedisTemplate redis;

    public BotLotteryService(BotLotterySettingsService settingsService,
                             BotLotteryDrawRepository drawRepository,
                             BotEmailBindingService bindingService,
                             Sub2ApiUserCache userCache,
                             Sub2UserUsageReader usageReader,
                             PlatformRepository platformRepository,
                             PoolAdminKeyResolver adminKeyResolver,
                             Sub2UserAdminClient adminClient,
                             StringRedisTemplate redis) {
        this.settingsService = settingsService;
        this.drawRepository = drawRepository;
        this.bindingService = bindingService;
        this.userCache = userCache;
        this.usageReader = usageReader;
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.adminClient = adminClient;
        this.redis = redis;
    }

    /** 执行或重试当天抽奖。 */
    public String draw(Long qq) {
        BotLotterySettings settings = settingsService.current();
        if (!settings.enabled()) {
            return "抽奖活动暂未开启。";
        }
        Optional<String> email = bindingService.trustedEmail(qq);
        if (email.isEmpty()) {
            return "尚未识别你的平台邮箱。请先发送「/绑定 <邮箱>」并完成验证。";
        }
        if (!usageReader.isAvailable()) {
            return "抽奖暂时不可用：未配置 Sub2API 只读库连接。";
        }

        String lockToken = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = acquireLock(lockToken);
        } catch (RuntimeException ex) {
            log.warn("获取抽奖锁失败: {}", ex.getMessage());
            return "抽奖服务暂时不可用，请稍后再试。";
        }
        if (!locked) {
            return "当前抽奖人数较多，请稍后再试。";
        }
        try {
            LocalDate today = LocalDate.now(ZONE);
            Optional<BotLotteryDrawEntity> existing = drawRepository.findByUserAndDate(
                    CHANNEL, String.valueOf(qq), today);
            if (existing.isPresent()) {
                return handleExisting(existing.get());
            }

            Connection connection = connection();
            if (connection.error() != null) {
                return connection.error();
            }
            Long userId = resolveUserId(connection, email.get());
            if (userId == null) {
                return "没有找到你的平台账号，请联系管理员。";
            }

            UsageWindow usageWindow = usageWindowFor(today);
            OffsetDateTime usageStart = usageWindow.start();
            OffsetDateTime usageEnd = usageWindow.end();
            BigDecimal usage = usageReader.actualCostByUser(userId, usageStart, usageEnd);
            if (usage.compareTo(settings.minUsageDaily()) < 0) {
                BigDecimal missing = settings.minUsageDaily().subtract(usage).max(BigDecimal.ZERO);
                return "昨天（" + usageStart.toLocalDate().format(DATE) + "）实际消费 " + money(usage)
                        + "，还差 " + money(missing) + " 才能抽奖。";
            }

            BigDecimal multiplier = randomMultiplier(settings);
            BigDecimal reward = calculateReward(usage, settings, multiplier);
            if (reward.signum() <= 0) {
                BotLotteryDrawEntity noPrize = newDraw(qq, email.get(), userId, today,
                        usageStart, usageEnd, usage, settings, multiplier, BigDecimal.ZERO,
                        "NO_PRIZE");
                drawRepository.insert(noPrize);
                return "昨天实际消费 " + money(usage) + "。\n本次未获得余额奖励，今天可抽奖机会已使用。";
            }

            BigDecimal reserved = drawRepository.reservedBudget(today, OffsetDateTime.now().minus(PENDING_BUDGET_TTL));
            BigDecimal remainingBudget = settings.dailyBudget().subtract(reserved);
            if (remainingBudget.compareTo(reward) < 0) {
                return "今日奖池预算已用完，请明天再试。";
            }

            BotLotteryDrawEntity draw = newDraw(qq, email.get(), userId, today,
                    usageStart, usageEnd, usage, settings, multiplier, reward, "PENDING");
            try {
                drawRepository.insert(draw);
            } catch (DuplicateKeyException ex) {
                return drawRepository.findByUserAndDate(CHANNEL, String.valueOf(qq), today)
                        .map(this::handleExisting)
                        .orElse("抽奖记录正在处理中，请稍后再试。");
            }
            return credit(draw, connection);
        } finally {
            releaseLock(lockToken);
        }
    }

    /** 查看抽奖规则。 */
    public String rules() {
        BotLotterySettings settings = settingsService.current();
        LocalDate today = LocalDate.now(ZONE);
        String usageDate = today.minusDays(1).format(DATE);
        return """
                QQ 机器人每日消费抽奖
                - 资格口径：%s 00:00–24:00 的 actual_cost（实际扣余额消费）
                - 最低消费：%s
                - 中奖比例：%s%%
                - 随机系数：%s ~ %s
                - 平均奖励率：%s%%
                - 单人单次最高奖励：%s
                - 每日总预算：%s
                - 每人每天：1 次

                发送「/抽奖」参与。管理员可用 /抽奖记录 查看最近记录。
                """.formatted(
                usageDate,
                money(settings.minUsageDaily()),
                percent(settings.payoutRatio()),
                settings.multiplierMin().stripTrailingZeros().toPlainString(),
                settings.multiplierMax().stripTrailingZeros().toPlainString(),
                percent(settings.effectiveAverageRatio()),
                money(settings.maxReward()),
                money(settings.dailyBudget()));
    }

    /** 用户自己的最近抽奖记录。 */
    public String recent(Long qq) {
        List<BotLotteryDrawEntity> rows = drawRepository.recentByUser(CHANNEL, String.valueOf(qq), 10);
        if (rows.isEmpty()) {
            return "还没有抽奖记录。发送「/抽奖」参与。";
        }
        StringBuilder sb = new StringBuilder("最近抽奖记录：");
        for (BotLotteryDrawEntity row : rows) {
            sb.append('\n').append(row.getDrawDate().format(DATE))
                    .append(" 消费 ").append(money(row.getEligibleUsage()))
                    .append("，奖励 ").append(money(row.getRewardAmount()))
                    .append("（").append(statusText(row.getStatus())).append("）");
            if ("FAILED".equals(row.getStatus()) && row.getFailureReason() != null) {
                sb.append("：").append(row.getFailureReason());
            }
        }
        return sb.toString();
    }

    private String handleExisting(BotLotteryDrawEntity draw) {
        if ("CREDITED".equals(draw.getStatus())) {
            return "今天已经抽过奖了。\n昨天消费 " + money(draw.getEligibleUsage())
                    + "，奖励 " + money(draw.getRewardAmount()) + " 已到账。";
        }
        if ("NO_PRIZE".equals(draw.getStatus())) {
            return "今天已经抽过奖了。\n昨天消费 " + money(draw.getEligibleUsage())
                    + "，本次未获得余额奖励。";
        }
        if (draw.getRewardAmount() == null || draw.getRewardAmount().signum() <= 0) {
            drawRepository.markNoPrize(draw.getId());
            return "本次未获得余额奖励，今天可抽奖机会已使用。";
        }
        BotLotterySettings settings = settingsService.current();
        BigDecimal otherReserved = drawRepository.reservedBudget(
                draw.getDrawDate(), OffsetDateTime.now().minus(PENDING_BUDGET_TTL), draw.getId());
        if (settings.dailyBudget().subtract(otherReserved).compareTo(draw.getRewardAmount()) < 0) {
            return "今日奖池预算已用完，暂时无法补发本次奖励，请联系管理员。";
        }
        Connection connection = connection();
        if (connection.error() != null) {
            return connection.error();
        }
        return credit(draw, connection);
    }

    private String credit(BotLotteryDrawEntity draw, Connection connection) {
        try {
            adminClient.updateBalance(connection.baseUrl(), connection.adminKey(), draw.getPlatformUserId(),
                    draw.getRewardAmount(), "QQ机器人每日抽奖奖励 " + draw.getDrawDate().format(DATE),
                    draw.getIdempotencyKey());
            OffsetDateTime creditedAt = OffsetDateTime.now();
            drawRepository.markCredited(draw.getId(), creditedAt);
            return "昨天实际消费 " + money(draw.getEligibleUsage()) + "。\n"
                    + "抽奖结果：" + money(draw.getRewardAmount()) + "\n"
                    + "奖励已发放到 Sub2API 余额。";
        } catch (RuntimeException ex) {
            String reason = safeMessage(ex);
            drawRepository.markFailed(draw.getId(), reason);
            log.warn("抽奖奖励发放失败: qq={}, drawId={}, reason={}", draw.getExternalUserId(), draw.getId(), reason);
            return "抽中 " + money(draw.getRewardAmount()) + "，但余额发放失败。\n"
                    + "请稍后再次发送「/抽奖」重试，不会重复发放。\n原因：" + reason;
        }
    }

    private static BotLotteryDrawEntity newDraw(Long qq, String email, Long userId, LocalDate drawDate,
                                                OffsetDateTime usageStart, OffsetDateTime usageEnd,
                                                BigDecimal usage, BotLotterySettings settings,
                                                BigDecimal multiplier, BigDecimal reward, String status) {
        BotLotteryDrawEntity entity = new BotLotteryDrawEntity();
        entity.setChannel(CHANNEL);
        entity.setExternalUserId(String.valueOf(qq));
        entity.setEmail(email);
        entity.setPlatformUserId(userId);
        entity.setDrawDate(drawDate);
        entity.setUsageWindowStart(usageStart);
        entity.setUsageWindowEnd(usageEnd);
        entity.setEligibleUsage(usage);
        entity.setPayoutRatio(settings.payoutRatio());
        entity.setMultiplier(multiplier);
        entity.setRewardAmount(reward);
        entity.setStatus(status);
        entity.setIdempotencyKey("qq-lottery-" + drawDate.format(DATE) + "-" + qq);
        return entity;
    }

    static BigDecimal calculateReward(BigDecimal usage, BotLotterySettings settings, BigDecimal multiplier) {
        return usage.multiply(settings.payoutRatio())
                .multiply(multiplier)
                .min(settings.maxReward())
                .setScale(2, RoundingMode.DOWN);
    }

    static UsageWindow usageWindowFor(LocalDate drawDate) {
        return new UsageWindow(
                drawDate.minusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),
                drawDate.atStartOfDay(ZONE).toOffsetDateTime());
    }

    private static BigDecimal randomMultiplier(BotLotterySettings settings) {
        BigDecimal span = settings.multiplierMax().subtract(settings.multiplierMin());
        return settings.multiplierMin()
                .add(span.multiply(BigDecimal.valueOf(RANDOM.nextDouble())))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private Connection connection() {
        PlatformEntity platform = platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .findFirst()
                .orElse(null);
        if (platform == null) {
            return Connection.error("平台还没配置好（未标记号池监控源），请联系管理员。");
        }
        String adminKey = adminKeyResolver.resolve(platform).orElse(null);
        if (adminKey == null) {
            return Connection.error("平台管理员密钥未配置，请联系管理员。");
        }
        return new Connection(platform.getUrl(), adminKey, null);
    }

    private Long resolveUserId(Connection connection, String email) {
        Long cached = userCache.userId(email).orElse(null);
        if (cached != null) {
            return cached;
        }
        return adminClient.findUserByEmail(connection.baseUrl(), connection.adminKey(), email)
                .map(Sub2AdminUser::id)
                .orElse(null);
    }

    private boolean acquireLock(String token) {
        Boolean acquired = redis.opsForValue().setIfAbsent(DRAW_LOCK_KEY, token, DRAW_LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock(String token) {
        try {
            String current = redis.opsForValue().get(DRAW_LOCK_KEY);
            if (token.equals(current)) {
                redis.delete(DRAW_LOCK_KEY);
            }
        } catch (RuntimeException ex) {
            log.warn("释放抽奖锁失败，将依赖 TTL: {}", ex.getMessage());
        }
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "$0";
        }
        return "$" + value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "CREDITED" -> "已发放";
            case "FAILED" -> "发放失败";
            case "PENDING" -> "处理中";
            case "NO_PRIZE" -> "未中奖";
            default -> status;
        };
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private record Connection(String baseUrl, String adminKey, String error) {
        static Connection error(String message) {
            return new Connection(null, null, message);
        }
    }

    record UsageWindow(OffsetDateTime start, OffsetDateTime end) {
    }
}
