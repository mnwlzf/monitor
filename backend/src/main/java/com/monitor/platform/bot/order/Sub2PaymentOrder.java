package com.monitor.platform.bot.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sub2API 支付订单（{@code public.payment_orders} 的一行）。
 *
 * <p>只映射 092 建表就存在的列：后续迁移新增的 {@code out_trade_no} /
 * {@code provider_key} / {@code provider_snapshot} / {@code bonus_amount} 一律没取，
 * 免得部署版本还没跑到那些迁移时直接报 {@code column does not exist}。</p>
 *
 * @param id           订单主键
 * @param userId       下单用户主键
 * @param userEmail    下单时快照的邮箱
 * @param userName     下单时快照的用户名
 * @param amount       到账金额
 * @param payAmount    实付金额
 * @param orderType    订单类型（{@code balance} 为余额充值）
 * @param paymentType  支付方式
 * @param status       订单状态
 * @param refundAmount 已退款金额
 * @param createdAt    创建时间
 * @param paidAt       支付时间
 * @param completedAt  完成时间
 * @param failedAt     失败时间
 * @param failedReason 失败原因
 */
public record Sub2PaymentOrder(
        long id,
        long userId,
        String userEmail,
        String userName,
        BigDecimal amount,
        BigDecimal payAmount,
        String orderType,
        String paymentType,
        String status,
        BigDecimal refundAmount,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime completedAt,
        OffsetDateTime failedAt,
        String failedReason
) {
}
