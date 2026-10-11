package com.monitor.platform.bot.image;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次消息处理里生成的图片，按请求持有。
 *
 * <p>为什么要它：现在的回复链路是 {@code BotReport → 文本 / 渲染图}，
 * 而生图是工具在模型调用过程中<strong>产生</strong>的一张独立图片，得先攒在请求级对象里，
 * 等模型答完再连同文字一起发出去。</p>
 *
 * <p>刻意不用 ThreadLocal：对象由调用方创建、显式传参，谁产生谁负责，读起来也更清楚。</p>
 */
public class GeneratedImages {

    private final List<byte[]> images = new ArrayList<>();

    public void add(byte[] image) {
        if (image != null && image.length > 0) {
            images.add(image);
        }
    }

    public boolean isEmpty() {
        return images.isEmpty();
    }

    public int size() {
        return images.size();
    }

    /** 取快照，避免发送过程中被并发修改。 */
    public List<byte[]> snapshot() {
        return List.copyOf(images);
    }
}
