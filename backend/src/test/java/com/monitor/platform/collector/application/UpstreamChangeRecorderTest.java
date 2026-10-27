package com.monitor.platform.collector.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

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
     * 「使用中」= 我自己的平台在用它：这个渠道里有本平台号池账号绑定的上游 Key。
     *
     * <p>上游把渠道标成启用只是在售（页面标「活跃」），不代表我在用 ——
     * 所以判定只看绑定关系，不看上游 status。</p>
     */
    @Test
    void shouldMarkChannelInUseOnlyWhenBoundKeyIsInIt() {
        Set<String> inUseChannels = Set.of("官Key-不降智-无6.1sol");

        assertTrue(UpstreamChangeRecorder.groupInUse("官Key-不降智-无6.1sol", "ratio", inUseChannels));
        // 实时告警必须严格同名：前缀相似的其它渠道不能被误判为使用中
        assertFalse(UpstreamChangeRecorder.groupInUse("官Key-不降智", "ratio",
                Set.of("官Key-不降智-无6.1sol")));
        assertTrue(UpstreamChangeRecorder.groupInUse(" 官Key-不降智 ", "ratio",
                Set.of("官key-不降智")));
        // 渠道里没有我绑定的 Key（哪怕上游是 active）不算使用中
        assertFalse(UpstreamChangeRecorder.groupInUse("没人用的渠道", "ratio", inUseChannels));
        assertFalse(UpstreamChangeRecorder.groupInUse("官Key-不降智-无6.1sol", "ratio", Set.of()));
    }

    /** 上游已移除的渠道不算使用中：此刻已经用不到了。 */
    @Test
    void shouldNotMarkInUseWhenGroupRemovedFromUpstream() {
        assertFalse(UpstreamChangeRecorder.groupInUse("官Key-不降智", "is_active", Set.of("官Key-不降智")));
    }    /** 值不可比较时（缺值、非数字）保守处理：不误报警告。 */
    @Test
    void shouldNotWarnWhenValuesAreNotComparable() {
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", null, new BigDecimal("1.5")));
        assertEquals("INFO", UpstreamChangeRecorder.groupChangeSeverity(
                "RATE_CHANGED", "abc", "def"));
    }
}
