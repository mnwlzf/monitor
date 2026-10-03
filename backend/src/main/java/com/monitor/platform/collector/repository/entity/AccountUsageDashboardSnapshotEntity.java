package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号用量看板快照实体。
 *
 * <p>公共指标使用强类型字段，保证跨平台查询口径一致；平台特有明细统一存放在
 * {@code metrics} JSONB 中，新增平台接入时无需调整表结构。</p>
 */
@TableName(value = "account_usage_dashboard_snapshots", autoResultMap = true)
public class AccountUsageDashboardSnapshotEntity {

    @TableId(type = IdType.AUTO)
    /** 快照主键。 */
    private Long id;
    /** 账号主键。 */
    private Integer accountId;
    /** 所属采集批次主键。 */
    private Long collectionRunId;
    /** 平台类型。 */
    private String platformType;
    /** 账户余额。 */
    private BigDecimal balance;
    /** 冻结余额。 */
    private BigDecimal frozenBalance;
    /** 累计请求数。 */
    private Long totalRequests;
    /** 累计 Token 数。 */
    private Long totalTokens;
    /** 累计标准消耗。 */
    private BigDecimal totalCost;
    /** 累计实际消耗。 */
    private BigDecimal totalActualCost;
    /** 跨平台公共指标，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metrics;
    /** 平台特有统计，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String platformStats;
    /** 上游原始响应，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;
    /** 采集时间。 */
    private OffsetDateTime collectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public Long getCollectionRunId() { return collectionRunId; }
    public void setCollectionRunId(Long collectionRunId) { this.collectionRunId = collectionRunId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public BigDecimal getFrozenBalance() { return frozenBalance; }
    public void setFrozenBalance(BigDecimal frozenBalance) { this.frozenBalance = frozenBalance; }
    public Long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(Long totalRequests) { this.totalRequests = totalRequests; }
    public Long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public BigDecimal getTotalActualCost() { return totalActualCost; }
    public void setTotalActualCost(BigDecimal totalActualCost) { this.totalActualCost = totalActualCost; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public String getPlatformStats() { return platformStats; }
    public void setPlatformStats(String platformStats) { this.platformStats = platformStats; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(OffsetDateTime collectedAt) { this.collectedAt = collectedAt; }
}