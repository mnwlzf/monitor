package com.monitor.platform.collector.repository.entity;

import java.math.BigDecimal;

/**
 * New API 账号在指定时间窗内的首末累计成本。
 *
 * <p>仅用于计算「今日消耗」差值，避免读取整条快照及 JSONB 原始数据。</p>
 */
public class AccountCostRangeEntity {

    private Integer accountId;
    private BigDecimal firstCost;
    private BigDecimal lastCost;

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(Integer accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getFirstCost() {
        return firstCost;
    }

    public void setFirstCost(BigDecimal firstCost) {
        this.firstCost = firstCost;
    }

    public BigDecimal getLastCost() {
        return lastCost;
    }

    public void setLastCost(BigDecimal lastCost) {
        this.lastCost = lastCost;
    }
}