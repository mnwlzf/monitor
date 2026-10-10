package com.monitor.platform.bot.report;

import com.monitor.platform.bot.BotFonts;
import com.monitor.platform.bot.QqBotProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格 / 热力图渲染。
 *
 * <p>中文字体随 JAR 打包，所以这里可以下强断言：不论本地、CI 还是容器，
 * 渲染都必须成功。这条测试同时也是「内置字体没被打进产物」的看门狗。</p>
 */
class BotReportRendererTest {

    private final QqBotProperties properties = new QqBotProperties();
    private final BotFonts fonts = new BotFonts(properties);
    private final BotReportRenderer renderer = new BotReportRenderer(properties, fonts);

    private static BotReport tableReport() {
        List<BotReport.Row> rows = new ArrayList<>();
        rows.add(BotReport.Row.of("NewAPI 主站", "newapi", "12", "1", "1234.56"));
        rows.add(BotReport.Row.of("Sub2API 自建", "sub2api", "8", "0", "987.65"));
        BotReport.Block table = new BotReport.Block("一、上游平台", List.of(
                BotReport.Col.left("平台"),
                BotReport.Col.left("类型"),
                BotReport.Col.right("账号数"),
                BotReport.Col.right("异常"),
                BotReport.Col.right("余额合计")), rows, null);
        return BotReport.of("上游平台概览", List.of(table), List.of("合计 2 个平台"));
    }

    /** 表格 + 热力色块混排：号池报表就长这样。 */
    private static BotReport heatmapReport() {
        List<BotReport.Row> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<BotReport.Cell> cells = new ArrayList<>(List.of(
                    BotReport.Cell.of("openai / 账号" + i),
                    BotReport.Cell.of("98.0%"),
                    BotReport.Cell.of("5.4s"),
                    BotReport.Cell.of("98.4"),
                    BotReport.Cell.of("813")));
            for (int b = 0; b < 12; b++) {
                cells.add(BotReport.Cell.heat((i + b) % 5 == 0 ? null : ((i * 12 + b) % 100) / 100.0));
            }
            rows.add(new BotReport.Row(cells));
        }
        List<BotReport.Col> cols = new ArrayList<>(List.of(
                BotReport.Col.left("平台 / 账号"),
                BotReport.Col.right("缓存率"),
                BotReport.Col.right("首 TOKEN"),
                BotReport.Col.right("每秒 TOKEN"),
                BotReport.Col.right("请求")));
        for (int b = 0; b < 12; b++) {
            cols.add(BotReport.Col.heat("10-0" + (b % 9 + 1) + " " + String.format("%02d:00", b * 2)));
        }
        BotReport.Block table = new BotReport.Block("一、有流量的渠道（近 24h）", cols, rows, null);
        return BotReport.of("号池监控（近 24h）", List.of(table), List.of("色块为该时段缓存命中率"));
    }

    @Test
    void shouldRenderTableReport() throws Exception {
        assertTrue(renderer.isAvailable(), "内置字体可用时渲染器应报告可用");

        BufferedImage decoded = decode(renderer.render(tableReport()));
        assertTrue(decoded.getWidth() >= 360);
        assertTrue(decoded.getHeight() > 100);
    }

    @Test
    void shouldRenderHeatmapReport() throws Exception {
        BufferedImage decoded = decode(renderer.render(heatmapReport()));
        // 热力图列多，图片应明显更宽
        assertTrue(decoded.getWidth() > 700, "热力图应较宽，实际 " + decoded.getWidth());
    }

    /**
     * 热力配色要和前端 PoolView 一致：{@code hsl(ratio*120, 62%, 52%)}，
     * 0 最差（红）→ 1 最好（绿），无数据灰色且不用 0 冒充。
     */
    @Test
    void shouldMatchFrontendHeatColors() {
        assertEquals(0xE5E9EC, BotReportRenderer.heatColor(null).getRGB() & 0xFFFFFF);
        assertEquals(0xE5E9EC, BotReportRenderer.heatColor(Double.NaN).getRGB() & 0xFFFFFF);

        Color worst = BotReportRenderer.heatColor(0.0);
        Color best = BotReportRenderer.heatColor(1.0);
        assertTrue(worst.getRed() > worst.getGreen() && worst.getRed() > worst.getBlue(),
                "0 应是红色系，实际 " + worst);
        assertTrue(best.getGreen() > best.getRed() && best.getGreen() > best.getBlue(),
                "1 应是绿色系，实际 " + best);

        // 色相随 ratio 线性推进：0.25 比 0 更"黄"（红仍最大，但绿分量明显更高）
        Color quarter = BotReportRenderer.heatColor(0.25);
        assertTrue(quarter.getGreen() > worst.getGreen(), "ratio 越大绿分量应越高");

        // 越界值要被夹紧，而不是画出奇怪的颜色
        assertEquals(best.getRGB(), BotReportRenderer.heatColor(9.9).getRGB());
        assertEquals(worst.getRGB(), BotReportRenderer.heatColor(-1.0).getRGB());
    }

    /**
     * 大模型用 emoji 方块表示热度，但内置中文字体不含 emoji 字形 ——
     * 直接当文字画会变成空白或豆腐块（线上就是这么坏掉的）。
     * 渲染器必须把它们画成真正的色块。
     */
    @Test
    void shouldRenderHeatEmojiAsColorSwatches() throws Exception {
        BotReport report = BotReport.fromMarkdown("""
                | 渠道 | 热度 |
                |---|---|
                | A | 🟩🟨🟧🟥⬜ |
                """);

        BufferedImage image = decode(renderer.render(report));
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        // 每个 emoji 都必须落成对应档位的颜色（与页面同一条色阶），
        // 且五档颜色互不相同 —— 否则说明 emoji 被当成了普通文字（豆腐块）
        Set<Integer> expected = Set.of(
                rgb(BotReportRenderer.heatColor(0.95)),
                rgb(BotReportRenderer.heatColor(0.82)),
                rgb(BotReportRenderer.heatColor(0.67)),
                rgb(BotReportRenderer.heatColor(0.35)),
                rgb(BotReportRenderer.heatColor(null)));
        assertEquals(5, expected.size(), "五档颜色应互不相同");
        for (int color : expected) {
            assertTrue(colors.contains(color),
                    String.format("缺少色块 #%06X —— emoji 没有被画成颜色", color));
        }
    }

    /** 图例那种「色块 + 文字」混排也要能画。 */
    @Test
    void shouldRenderMixedSwatchAndText() throws Exception {
        BotReport report = BotReport.fromMarkdown("色阶按缓存命中率着色：🟩 ≥90% | 🟨 75–90% | 🟥 <60%");
        BufferedImage image = decode(renderer.render(report));
        assertTrue(image.getHeight() > 60);
    }
    @Test
    void shouldReturnNullWhenDisabled() {
        QqBotProperties off = new QqBotProperties();
        off.setImageEnabled(false);
        BotReportRenderer disabled = new BotReportRenderer(off, new BotFonts(off));
        assertNull(disabled.render(tableReport()));
    }

    @Test
    void shouldReturnNullForEmptyReport() {
        assertNull(renderer.render(null));
        assertNull(renderer.render(BotReport.of("", List.of(), List.of())));
    }

    private static int rgb(Color color) {
        return color.getRGB() & 0xFFFFFF;
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        assertNotNull(png, "内置字体可用时必须渲染出图片");
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "产出的字节应能被解码成图片");
        return image;
    }
}