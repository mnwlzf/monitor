package com.monitor.platform.bot.image;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * 这一轮里工具想告诉用户、但模型<strong>未必会转达</strong>的话。
     *
     * <p>典型场景：生图被全局限频挡下。工具的失败原因是作为「工具返回值」回到模型手里的，
     * 模型有可能把它吞掉甚至答成空白。所以发送层会兜底，保证用户一定看到原因。</p>
     */
    private volatile String notice;

    /** 「正在画…」这类开场提示一轮只发一次（模型可能连着调几次工具）。 */
    private final AtomicBoolean startNotified = new AtomicBoolean();

    public void add(byte[] image) {
        if (image != null && image.length > 0) {
            images.add(image);
        }
    }

    /**
     * 抢占「本轮是否已经提示过开始了」。
     *
     * @return true 表示本次调用是第一个，调用方应当去发那句开场提示
     */
    public boolean markStartNotified() {
        return startNotified.compareAndSet(false, true);
    }

    /** 记下一条必须让用户看到的话（覆盖式，只保留最新一条）。 */
    public void notice(String message) {
        if (message != null && !message.isBlank()) {
            this.notice = message;
        }
    }

    /** 有没有必须转达给用户的话。 */
    public String notice() {
        return notice;
    }

    public boolean isEmpty() {        return images.isEmpty();
    }

    public int size() {
        return images.size();
    }

    /** 取快照，避免发送过程中被并发修改。 */
    public List<byte[]> snapshot() {
        return List.copyOf(images);
    }
}
