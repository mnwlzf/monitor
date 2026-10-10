package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长回复转图片渲染。
 *
 * <p>渲染依赖中文字体（JRE 自带字体不含中文）。CI 会先装 fonts-wqy-microhei，
 * 但为了不让「没装字体」变成红构建，这里对两种情况都做断言：
 * 有字体时校验产出的确实是一张可解码的 PNG，没字体时校验优雅降级为 null。</p>
 */
class BotImageRendererTest {

    private static final String SAMPLE = """
            号池监控（近 24h）：
            - 账号A [newapi]：请求 1234，缓存率 82.3%，首 Token 450ms
            - 账号B [newapi]：请求 2345，缓存率 76.1%，首 Token 500ms
            合计：请求 5678，整体缓存率 79.1%""";

    @Test
    void shouldRenderDecodablePng() throws Exception {
        BotImageRenderer renderer = new BotImageRenderer(new QqBotProperties());

        byte[] png = renderer.renderPng(SAMPLE);
        if (png == null) {
            // 没有中文字体时必须自报家门，而不是悄悄返回一张豆腐块图片
            assertFalse(renderer.isAvailable(), "返回 null 时应报告字体不可用");
            return;
        }

        assertTrue(renderer.isAvailable());
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
        BotImageRenderer renderer = new BotImageRenderer(properties);

        byte[] png = renderer.renderPng(SAMPLE);
        if (png == null) {
            return;
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(520, decoded.getWidth());
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