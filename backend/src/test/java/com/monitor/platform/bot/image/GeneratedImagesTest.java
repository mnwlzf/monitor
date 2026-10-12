package com.monitor.platform.bot.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 每轮对话的产物容器：图片 + 「必须转达给用户的话」。
 *
 * <p>notice 存在的理由：生图被限频时，失败原因是作为工具返回值回到模型手里的，
 * 模型有可能吞掉甚至答成空白。发送层靠这个兜底，保证用户一定看到原因。</p>
 */
class GeneratedImagesTest {

    /** 只保留最新一条 —— 一轮里工具可能被调用多次。 */
    @Test
    void shouldKeepLatestNotice() {
        GeneratedImages generated = new GeneratedImages();

        assertNull(generated.notice());
        generated.notice("第一句");
        generated.notice("第二句");

        assertEquals("第二句", generated.notice());
    }

    @Test
    void shouldIgnoreBlankNotice() {
        GeneratedImages generated = new GeneratedImages();

        generated.notice("   ");

        assertNull(generated.notice());
    }

    /** notice 不该被当成图片 —— 否则发送层会以为「已经出图了」而跳过兜底。 */
    @Test
    void shouldNotTreatNoticeAsImage() {
        GeneratedImages generated = new GeneratedImages();
        generated.notice("生图太频繁了");

        generated.add(new byte[]{1, 2, 3});

        assertFalse(generated.isEmpty());
        assertEquals(1, generated.size());
        assertEquals("生图太频繁了", generated.notice());
    }

    /** 空图片不该被收进来。 */
    @Test
    void shouldIgnoreEmptyImage() {
        GeneratedImages generated = new GeneratedImages();

        generated.add(new byte[0]);
        generated.add(null);

        assertEquals(0, generated.size());
    }
}
