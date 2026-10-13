package com.monitor.platform.collector.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 采集过程中的数值换算与比较。
 *
 * <p>纯函数，无状态；从 {@code CollectionService} 抽出，供采集编排、快照写入等共用，
 * 避免同一套换算逻辑在多处各写一份。</p>
 */
final class CollectionValues {

    /** New API 中 1 USD 对应的内部额度数量。 */
    private static final BigDecimal NEW_API_QUOTA_PER_USD = BigDecimal.valueOf(500000L);

    private CollectionValues() {
    }

    /** Long 型上游额度转 BigDecimal。 */
    static BigDecimal decimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** Double 型上游金额转 BigDecimal。 */
    static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 使用数值比较判断两个 BigDecimal 是否不同，避免 1.0 与 1.00 被误判。 */
    static boolean different(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left != right;
        }
        return left.compareTo(right) != 0;
    }

    /** 截断过长的文本，避免超过数据库字段长度。 */
    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 将 New API 原始 quota 换算为 USD，为空时返回 null。 */
    static BigDecimal newApiUsd(Long quota) {
        BigDecimal value = decimal(quota);
        return value == null ? null : value.divide(NEW_API_QUOTA_PER_USD, 8, RoundingMode.HALF_UP);
    }

    /** New API 1 USD 对应的内部额度数量，供前端换算展示。 */
    static BigDecimal newApiQuotaPerUsd() {
        return NEW_API_QUOTA_PER_USD;
    }
}
