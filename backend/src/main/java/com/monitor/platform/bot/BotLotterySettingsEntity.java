package com.monitor.platform.bot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 页面可配置的每日消费抽奖参数（全局单行）。 */
public class BotLotterySettingsEntity {

    private Integer id;
    private Boolean enabled;
    private BigDecimal minUsageDaily;
    private BigDecimal payoutRatio;
    private BigDecimal multiplierMin;
    private BigDecimal multiplierMax;
    private BigDecimal maxReward;
    private BigDecimal dailyBudget;
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public BigDecimal getMinUsageDaily() { return minUsageDaily; }
    public void setMinUsageDaily(BigDecimal minUsageDaily) { this.minUsageDaily = minUsageDaily; }
    public BigDecimal getPayoutRatio() { return payoutRatio; }
    public void setPayoutRatio(BigDecimal payoutRatio) { this.payoutRatio = payoutRatio; }
    public BigDecimal getMultiplierMin() { return multiplierMin; }
    public void setMultiplierMin(BigDecimal multiplierMin) { this.multiplierMin = multiplierMin; }
    public BigDecimal getMultiplierMax() { return multiplierMax; }
    public void setMultiplierMax(BigDecimal multiplierMax) { this.multiplierMax = multiplierMax; }
    public BigDecimal getMaxReward() { return maxReward; }
    public void setMaxReward(BigDecimal maxReward) { this.maxReward = maxReward; }
    public BigDecimal getDailyBudget() { return dailyBudget; }
    public void setDailyBudget(BigDecimal dailyBudget) { this.dailyBudget = dailyBudget; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
