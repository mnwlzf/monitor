package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道变更的级别判定。
 *
 * <p>线上问题：severity 以前写死 {@code INFO}，结果「渠道涨价」和「新增一个渠道」
 * 长得一模一样，全被噪声淹没 —— 而涨价是直接关系成本的，必须浮出来。</p>
 */
class UpstreamChangeRecorderTest {

    @Test
    void shouldWarnWhenRateGoesUp() {
        assertEquals("WARNING", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", new BigDecimal("1.0"), new BigDecimal("1.5")));
    }

    /** 降价是好事，不用当成警告打扰人。 */
    @Test
    void shouldNotWarnWhenRateGoesDown() {
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", new BigDecimal("1.5"), new BigDecimal("1.0")));
    }

    @Test
    void shouldWarnWhenBaseRateGoesUp() {
        assertEquals("WARNING", UpstreamChangeRecorder.groupChangeSeverity(
                "BASE_RATE_CHANGED", new BigDecimal("2.0"), new BigDecimal("2.5")));
    }

    @Test
    void shouldWarnWhenGroupRemoved() {
        assertEquals("WARNING", UpstreamChangeRecorder.groupChangeSeverity(
                "GROUP_REMOVED", null, null));
    }

    @Test
    void shouldStayInfoForOtherChanges() {
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity("GROUP_ADDED", null, null));
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity("GROUP_UPDATED", "a", "b"));
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity("STATUS_CHANGED", "1", "0"));
    }

    /**
     * 「使用中」= 上游仍然存在（is_active）且状态为启用（active / ACTIVE）。
     *
     * <p>只按 status 判是不够的：已从上游移除的渠道 status 可能还写着 active，
     * 但你已经用不到它了，不该进实时告警。</p>
     */
    @Test
    void shouldMarkGroupChangeInUseOnlyWhenStillActive() {
        UpstreamGroupEntity group = new UpstreamGroupEntity();
        group.setIsActive(true);

        group.setStatus("active");
        assertTrue(UpstreamChangeRecorder.groupInUse(group, "ratio", new BigDecimal("1.0"), new BigDecimal("2.0")));

        group.setStatus("ACTIVE");
        assertTrue(UpstreamChangeRecorder.groupInUse(group, "ratio", new BigDecimal("1.0"), new BigDecimal("2.0")));

        group.setStatus("disabled");
        assertFalse(UpstreamChangeRecorder.groupInUse(group, "ratio", new BigDecimal("1.0"), new BigDecimal("2.0")));
    }

    /** 上游已移除的渠道（is_active = false）不算使用中。 */
    @Test
    void shouldNotMarkInUseWhenGroupRemovedFromUpstream() {
        UpstreamGroupEntity group = new UpstreamGroupEntity();
        group.setStatus("active");
        group.setIsActive(false);

        assertFalse(UpstreamChangeRecorder.groupInUse(group, "ratio", new BigDecimal("1.0"), new BigDecimal("2.0")));

        // 移除事件本身也不算 —— 此刻已经用不到了
        group.setIsActive(true);
        assertFalse(UpstreamChangeRecorder.groupInUse(group, "is_active", true, false));
    }    /** 值不可比较时（缺值、非数字）保守处理：不误报警告。 */
    @Test
    void shouldNotWarnWhenValuesAreNotComparable() {
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", null, new BigDecimal("1.5")));
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", "abc", "def"));
    }
}
