package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 号池热力图的 emoji 分档。
 *
 * <p>大模型拿不到时间桶数据时只能自己编「热度」单列，粒度很粗。这里把细粒度数据
 * 以 emoji 色块的形式交给它，分档必须与页面图例严格一致，否则图和页面会对不上。</p>
 */
class MonitorChatToolsHeatEmojiTest {

    @Test
    void shouldMatchPageLegend() {
        assertEquals("🟩", MonitorChatTools.heatEmoji(0.90));
        assertEquals("🟩", MonitorChatTools.heatEmoji(0.991));
        assertEquals("🟨", MonitorChatTools.heatEmoji(0.75));
        assertEquals("🟨", MonitorChatTools.heatEmoji(0.899));
        assertEquals("🟧", MonitorChatTools.heatEmoji(0.60));
        assertEquals("🟧", MonitorChatTools.heatEmoji(0.749));
        assertEquals("🟥", MonitorChatTools.heatEmoji(0.599));
        assertEquals("🟥", MonitorChatTools.heatEmoji(0.0));
    }

    /** 无流量必须用灰色，不能用 0 冒充成「最差」。 */
    @Test
    void shouldUseGreyForNoTraffic() {
        assertEquals("⬜", MonitorChatTools.heatEmoji(null));
    }
}