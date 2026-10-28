package com.monitor.platform.bot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一次抽奖及余额发放记录。 */
public class BotLotteryDrawEntity {

    private Long id;
    private String channel;
    private String externalUserId;
    private String email;
    private Long platformUserId;
    private LocalDate drawDate;
    private OffsetDateTime usageWindowStart;
    private OffsetDateTime usageWindowEnd;
    private BigDecimal eligibleUsage;
    private BigDecimal payoutRatio;
    private BigDecimal multiplier;
    private BigDecimal rewardAmount;
    private String status;
    private String idempotencyKey;
    private String failureReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime creditedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getPlatformUserId() { return platformUserId; }
    public void setPlatformUserId(Long platformUserId) { this.platformUserId = platformUserId; }
    public LocalDate getDrawDate() { return drawDate; }
    public void setDrawDate(LocalDate drawDate) { this.drawDate = drawDate; }
    public OffsetDateTime getUsageWindowStart() { return usageWindowStart; }
    public void setUsageWindowStart(OffsetDateTime usageWindowStart) { this.usageWindowStart = usageWindowStart; }
    public OffsetDateTime getUsageWindowEnd() { return usageWindowEnd; }
    public void setUsageWindowEnd(OffsetDateTime usageWindowEnd) { this.usageWindowEnd = usageWindowEnd; }
    public BigDecimal getEligibleUsage() { return eligibleUsage; }
    public void setEligibleUsage(BigDecimal eligibleUsage) { this.eligibleUsage = eligibleUsage; }
    public BigDecimal getPayoutRatio() { return payoutRatio; }
    public void setPayoutRatio(BigDecimal payoutRatio) { this.payoutRatio = payoutRatio; }
    public BigDecimal getMultiplier() { return multiplier; }
    public void setMultiplier(BigDecimal multiplier) { this.multiplier = multiplier; }
    public BigDecimal getRewardAmount() { return rewardAmount; }
    public void setRewardAmount(BigDecimal rewardAmount) { this.rewardAmount = rewardAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCreditedAt() { return creditedAt; }
    public void setCreditedAt(OffsetDateTime creditedAt) { this.creditedAt = creditedAt; }
}
