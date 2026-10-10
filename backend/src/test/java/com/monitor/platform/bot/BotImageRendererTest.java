package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长回复转图片渲染。
 *
 * <p>中文字体随 JAR 打包，所以这里可以下强断言：不论在本地、CI 还是容器里，
 * 渲染都必须成功。这条测试同时也是「内置字体没被打进产物」的看门狗。</p>
 */
class BotImageRendererTest {

    private static final String BUNDLED_FONT = "/fonts/wqy-microhei.ttc";

    private static final String SAMPLE = """
            号池监控（近 24h）：
            - 账号A [newapi]：请求 1234，缓存率 82.3%，首 Token 450ms
            - 账号B [newapi]：请求 2345，缓存率 76.1%，首 Token 500ms
            合计：请求 5678，整体缓存率 79.1%""";

    /** 内置字体必须真的在 classpath 上，且能显示中文。 */
    @Test
    void shouldShipUsableBundledFont() throws Exception {
        try (InputStream in = BotImageRenderer.class.getResourceAsStream(BUNDLED_FONT)) {
            assertNotNull(in, "内置中文字体未打包进产物: " + BUNDLED_FONT);
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            assertEquals(-1, font.canDisplayUpTo("监控号池缓存余额渠道"),
                    "内置字体应能显示中文，实际 family=" + font.getFamily());
        }
    }

    @Test
    void shouldRenderDecodablePng() throws Exception {
        BotImageRenderer renderer = new BotImageRenderer(new QqBotProperties());
        assertTrue(renderer.isAvailable(), "内置字体可用时渲染器应报告可用");
        assertTrue(renderer.isUsingBundledFont(),
                "必须用随 JAR 打包的内置字体，不能悄悄退回系统字体；当前字体=" + renderer.fontName());

        byte[] png = renderer.renderPng(SAMPLE);
        assertNotNull(png, "内置字体可用时必须渲染出图片");
        assertTrue(png.length > 1000, "PNG 体积异常: " + png.length);

        // PNG magic number
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded, "产出的字节应能被解码成图片");
        assertTrue(decoded.getWidth() >= 360);
        assertTrue(decoded.getHeight() > 100);
    }

    /** 宽度配置要生效，且被夹在合理区间内。 */
    @Test
    void shouldRespectConfiguredWidth() throws Exception {
        QqBotProperties properties = new QqBotProperties();
        properties.setImageWidth(520);

        byte[] png = new BotImageRenderer(properties).renderPng(SAMPLE);
        assertNotNull(png);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(520, decoded.getWidth());
    }

    /** 内容很长时高度按行增长，但不失控。 */
    @Test
    void shouldGrowHeightWithContent() throws Exception {
        StringBuilder long_ = new StringBuilder("账户余额：");
        for (int i = 0; i < 40; i++) {
            long_.append("\n- 账号").append(i).append("：余额 12.34，已用额度 56.78");
        }

        byte[] png = new BotImageRenderer(new QqBotProperties()).renderPng(long_.toString());
        assertNotNull(png);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(decoded.getHeight() > 600, "行数多时图片应变高，实际 " + decoded.getHeight());
    }

    @Test
    void shouldReturnNullWhenDisabled() {
        QqBotProperties properties = new QqBotProperties();
        properties.setImageEnabled(false);
        assertNull(new BotImageRenderer(properties).renderPng(SAMPLE));
    }

    @Test
    void shouldReturnNullForBlankText() {
        assertNull(new BotImageRenderer(new QqBotProperties()).renderPng("   "));
        assertNull(new BotImageRenderer(new QqBotProperties()).renderPng(null));
    }
}