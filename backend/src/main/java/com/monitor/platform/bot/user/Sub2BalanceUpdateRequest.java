package com.monitor.platform.bot.user;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Sub2API 管理员余额调整请求。
 *
 * <p>机器人只使用 {@code add}，不开放 {@code set} / {@code subtract}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Sub2BalanceUpdateRequest(
        BigDecimal balance,
        String operation,
        String notes
) {
}
