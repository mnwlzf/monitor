package com.monitor.platform.collector.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 渠道变更的级别判定。
 *
 * <p>线上问题：severity 以前写死 {@code INFO}，结果「渠道涨价」和「新增一个渠道」
 * 长得一模一样，全被噪声淹没 —— 而涨价是直接关系成本的，必须浮出来。</p>
 */
class CollectionServiceChangeSeverityTest {

    @Test
    void shouldWarnWhenRateGoesUp() {
        assertEquals("WARNING", CollectionService.groupChangeSeverity(
                "RATE_CHANGED", new BigDecimal("1.0"), new BigDecimal("1.5")));
    }

    /** 降价是好事，不用当成警告打扰人。 */
    @Test
    void shouldNotWarnWhenRateGoesDown() {
        assertEquals("INFO", CollectionService.groupChangeSeverity(
                "RATE_CHANGED", new BigDecimal("1.5"), new BigDecimal("1.0")));
    }

    @Test
    void shouldWarnWhenBaseRateGoesUp() {
        assertEquals("WARNING", CollectionService.groupChangeSeverity(
                "BASE_RATE_CHANGED", new BigDecimal("2.0"), new BigDecimal("2.5")));
    }

    @Test
    void shouldWarnWhenGroupRemoved() {
        assertEquals("WARNING", CollectionService.groupChangeSeverity(
                "GROUP_REMOVED", null, null));
    }

    @Test
    void shouldStayInfoForOtherChanges() {
        assertEquals("INFO", CollectionService.groupChangeSeverity("GROUP_ADDED", null, null));
        assertEquals("INFO", CollectionService.groupChangeSeverity("GROUP_UPDATED", "a", "b"));
        assertEquals("INFO", CollectionService.groupChangeSeverity("STATUS_CHANGED", "1", "0"));
    }

    /** 值不可比较时（缺值、非数字）保守处理：不误报警告。 */
    @Test
    void shouldNotWarnWhenValuesAreNotComparable() {
        assertEquals("INFO", CollectionService.groupChangeSeverity(
                "RATE_CHANGED", null, new BigDecimal("1.5")));
        assertEquals("INFO", CollectionService.groupChangeSeverity(
                "RATE_CHANGED", "abc", "def"));
    }
}
