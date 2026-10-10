package com.monitor.platform.bot;

import com.monitor.platform.bot.report.BotReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型回答里 [[HEATMAP:24h]] 标记的处理。
 *
 * <p>热力图是 20 行 × 24 列以上的矩阵，大模型抄不准 —— 会把图案「重写」一遍，
 * 和页面完全对不上。所以改为：模型只输出标记，真正的热力图由后端确定性渲染。</p>
 */
class BotMessageServiceHeatmapMergeTest {

    private static BotReport heatmap() {
        BotReport.Block table = new BotReport.Block("一、有流量的渠道", List.of(
                BotReport.Col.left("平台 / 账号"),
                BotReport.Col.heat("10-09 15:00")), List.of(
                BotReport.Row.of("deepseek / workbuddy-免费版", "")), null);
        return BotReport.of("号池监控（近 24h）", List.of(table), List.of("色块为该时段缓存命中率"));
    }

    @Test
    void shouldAppendDeterministicHeatmapAfterComment() {
        BotReport comment = BotReport.fromMarkdown("近 24 小时整体健康，只有两个渠道偏低。");

        BotReport merged = BotMessageService.mergeHeatmap(comment, heatmap());

        assertEquals("号池监控（近 24h）", merged.title(), "没有标题时用热力图的标题");
        assertEquals(2, merged.blocks().size(), "模型的话 + 真实热力图都要在");
        assertTrue(merged.blocks().get(0).plain(), "第一块是模型的评论");
        assertEquals(1, merged.blocks().get(1).rows().size());
        assertEquals("色块为该时段缓存命中率", merged.notes().get(0));
    }

    @Test
    void shouldKeepModelTitleWhenPresent() {
        BotReport comment = BotReport.fromMarkdown("# 我看到的趋势\n整体健康。");

        BotReport merged = BotMessageService.mergeHeatmap(comment, heatmap());

        assertEquals("我看到的趋势", merged.title());
    }
}