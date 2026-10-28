package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotLotterySettingsRequest;
import com.monitor.platform.api.dto.BotLotterySettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/** 抽奖参数配置与内存快照。 */
@Service
public class BotLotterySettingsService {

    private static final Logger log = LoggerFactory.getLogger(BotLotterySettingsService.class);
    private static final BigDecimal DEFAULT_MIN_USAGE = new BigDecimal("10.0000000000");
    private static final BigDecimal DEFAULT_PAYOUT_RATIO = new BigDecimal("0.030000");
    private static final BigDecimal DEFAULT_MULTIPLIER_MIN = new BigDecimal("0.8000");
    private static final BigDecimal DEFAULT_MULTIPLIER_MAX = new BigDecimal("1.2000");
    private static final BigDecimal DEFAULT_MAX_REWARD = new BigDecimal("5.0000");
    private static final BigDecimal DEFAULT_DAILY_BUDGET = new BigDecimal("50.0000");

    private final BotLotterySettingsRepository repository;
    private volatile BotLotterySettings current;

    public BotLotterySettingsService(BotLotterySettingsRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        reload();
    }

    public BotLotterySettings current() {
        BotLotterySettings snapshot = current;
        if (snapshot == null) {
            reload();
            snapshot = current;
        }
        return snapshot;
    }

    public BotLotterySettingsResponse get() {
        BotLotterySettingsEntity entity = repository.find();
        if (entity == null) {
            entity = defaults();
        }
        return response(entity);
    }

    @Transactional
    public BotLotterySettingsResponse save(BotLotterySettingsRequest request) {
        BigDecimal ratio = normalize(request.payoutRatioPercent(), 6, "中奖比例")
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw BusinessException.of("中奖比例必须大于 0 且不超过 100%");
        }
        BigDecimal minUsage = normalize(request.minUsageDaily(), 10, "前一日最低消费");
        if (minUsage.signum() < 0) {
            throw BusinessException.of("最低消费不能小于 0");
        }
        BigDecimal multiplierMin = normalize(request.multiplierMin(), 4, "随机系数下限");
        BigDecimal multiplierMax = normalize(request.multiplierMax(), 4, "随机系数上限");
        if (multiplierMin.signum() <= 0 || multiplierMax.compareTo(multiplierMin) < 0) {
            throw BusinessException.of("随机系数必须大于 0，且上限不能小于下限");
        }
        BigDecimal maxReward = normalize(request.maxReward(), 4, "单次最高奖励");
        if (maxReward.signum() <= 0) {
            throw BusinessException.of("单次最高奖励必须大于 0");
        }
        BigDecimal dailyBudget = normalize(request.dailyBudget(), 4, "每日预算");
        if (dailyBudget.signum() <= 0) {
            throw BusinessException.of("每日预算必须大于 0");
        }

        BotLotterySettingsEntity entity = new BotLotterySettingsEntity();
        entity.setId(1);
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()));
        entity.setMinUsageDaily(minUsage);
        entity.setPayoutRatio(ratio);
        entity.setMultiplierMin(multiplierMin);
        entity.setMultiplierMax(multiplierMax);
        entity.setMaxReward(maxReward);
        entity.setDailyBudget(dailyBudget);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
        current = toSettings(entity);
        log.info("抽奖设置已更新: enabled={}, minUsage={}, ratio={}, multiplier={}~{}, maxReward={}, dailyBudget={}",
                current.enabled(), current.minUsageDaily(), current.payoutRatio(), current.multiplierMin(),
                current.multiplierMax(), current.maxReward(), current.dailyBudget());
        return response(entity);
    }

    void reload() {
        BotLotterySettingsEntity entity = repository.find();
        if (entity == null) {
            entity = defaults();
        }
        current = toSettings(entity);
    }

    private static BotLotterySettingsEntity defaults() {
        BotLotterySettingsEntity entity = new BotLotterySettingsEntity();
        entity.setId(1);
        entity.setEnabled(false);
        entity.setMinUsageDaily(DEFAULT_MIN_USAGE);
        entity.setPayoutRatio(DEFAULT_PAYOUT_RATIO);
        entity.setMultiplierMin(DEFAULT_MULTIPLIER_MIN);
        entity.setMultiplierMax(DEFAULT_MULTIPLIER_MAX);
        entity.setMaxReward(DEFAULT_MAX_REWARD);
        entity.setDailyBudget(DEFAULT_DAILY_BUDGET);
        entity.setUpdatedAt(null);
        return entity;
    }

    private static BotLotterySettings toSettings(BotLotterySettingsEntity entity) {
        return new BotLotterySettings(
                Boolean.TRUE.equals(entity.getEnabled()),
                decimal(entity.getMinUsageDaily(), DEFAULT_MIN_USAGE),
                decimal(entity.getPayoutRatio(), DEFAULT_PAYOUT_RATIO),
                decimal(entity.getMultiplierMin(), DEFAULT_MULTIPLIER_MIN),
                decimal(entity.getMultiplierMax(), DEFAULT_MULTIPLIER_MAX),
                decimal(entity.getMaxReward(), DEFAULT_MAX_REWARD),
                decimal(entity.getDailyBudget(), DEFAULT_DAILY_BUDGET));
    }

    private static BotLotterySettingsResponse response(BotLotterySettingsEntity entity) {
        BotLotterySettings settings = toSettings(entity);
        return new BotLotterySettingsResponse(
                settings.enabled(),
                settings.minUsageDaily(),
                settings.payoutRatio().multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                settings.multiplierMin(),
                settings.multiplierMax(),
                settings.maxReward(),
                settings.dailyBudget(),
                entity.getUpdatedAt());
    }

    private static BigDecimal decimal(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal normalize(BigDecimal value, int scale, String field) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            throw BusinessException.of(field + "不能为空");
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
