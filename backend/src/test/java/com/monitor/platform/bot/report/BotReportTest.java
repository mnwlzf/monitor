package com.monitor.platform.bot.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把大模型的 Markdown 回答解析成报表。
 *
 * <p>大模型没法直接产出 {@link BotReport.Block}，只会写 Markdown；
 * 解析对了，长回答才能渲染成表格图而不是一长串项目符号。</p>
 */
class BotReportTest {

    @Test
    void shouldParseMarkdownTable() {
        BotReport report = BotReport.fromMarkdown("""
                ## 上游平台概览

                | 平台 | 账号数 | 余额 |
                |---|---:|---:|
                | NewAPI | 12 | 1234.56 |
                | Sub2API | 8 | 987.65 |
                """);

        assertEquals(1, report.blocks().size());
        BotReport.Block table = report.blocks().get(0);
        assertEquals("上游平台概览", table.heading());
        assertFalse(table.plain());
        assertEquals(3, table.cols().size());
        assertEquals(2, table.rows().size());
        assertEquals("NewAPI", table.rows().get(0).cells().get(0).text());
        // 数值列表头右对齐
        assertEquals(BotReport.Align.RIGHT, table.cols().get(1).align());
    }

    @Test
    void shouldTreatHashHeadingAsTitle() {
        BotReport report = BotReport.fromMarkdown("# 号池监控\n\n正文一行");
        assertEquals("号池监控", report.title());
        assertEquals(1, report.blocks().size());
        assertTrue(report.blocks().get(0).plain());
    }

    /** 没有表格时退化成段落块，渲染结果与旧的纯文本图一致。 */
    @Test
    void shouldFallBackToParagraphs() {
        BotReport report = BotReport.fromMarkdown("号池监控：\n- 账号A：请求 100\n- 账号B：请求 200");
        assertEquals(1, report.blocks().size());
        assertTrue(report.blocks().get(0).plain());
        assertEquals(3, report.blocks().get(0).rows().size());
    }

    /** 表格前后的普通文字要各成一段，不能被吞掉。 */
    @Test
    void shouldKeepTextAroundTable() {
        BotReport report = BotReport.fromMarkdown("""
                先看总览：

                | 平台 | 余额 |
                |---|---:|
                | A | 1 |

                再看明细。
                """);
        assertEquals(3, report.blocks().size());
        assertTrue(report.blocks().get(0).plain());
        assertFalse(report.blocks().get(1).plain());
        assertTrue(report.blocks().get(2).plain());
    }

    /** 纯文本兜底必须保留 "- " 与原有换行，不能因为图片渲染改变文本观感。 */
    @Test
    void shouldKeepPlainTextShape() {
        BotReport report = BotReport.fromMarkdown("标题\n- 账号A：请求 100");
        assertEquals("标题\n- 账号A：请求 100", report.toPlainText());
    }

    @Test
    void shouldHandleBlankInput() {
        assertEquals("", BotReport.fromMarkdown(null).toPlainText());
        assertEquals("", BotReport.fromMarkdown("   ").toPlainText());
    }
}