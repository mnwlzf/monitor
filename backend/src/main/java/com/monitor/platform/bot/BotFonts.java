package com.monitor.platform.bot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片渲染用字体。
 *
 * <p>优先使用随 JAR 打包的中文字体（{@code /fonts/wqy-microhei.ttc}，文泉驿微米黑，Apache-2.0）：
 * JRE 自带字体不含中文，靠系统字体就会出现「本地能跑、容器里变豆腐块」。
 * 内置字体让本地开发、CI、容器的渲染结果完全一致，也不依赖构建期联网。
 * 只有内置字体缺失或损坏时才退回系统字体探测；都不可用时渲染器返回 null，
 * 由调用方退回截断文本。</p>
 *
 * <p>启动时（{@link PostConstruct}）就把探测跑一遍并打一行结论 ——
 * 否则懒加载会让启动日志里什么都看不到，排查「图片功能到底能不能用」只能靠猜。</p>
 */
@Component
public class BotFonts {

    private static final Logger log = LoggerFactory.getLogger(BotFonts.class);

    /** 随 JAR 打包的中文字体。 */
    private static final String BUNDLED_FONT = "/fonts/wqy-microhei.ttc";

    /** 用于探测字体是否真的能显示中文。 */
    private static final String CJK_PROBE = "监控号池缓存余额渠道";

    /** 常见中文字体，按优先级探测；都不可用时退回逻辑字体 SansSerif。 */
    private static final List<String> FONT_CANDIDATES = List.of(
            "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "Source Han Sans CN",
            "WenQuanYi Micro Hei", "WenQuanYi Zen Hei",
            "Microsoft YaHei", "微软雅黑", "SimHei", "黑体",
            "PingFang SC", "Hiragino Sans GB");

    private static final float TITLE_SIZE = 21f;
    private static final float HEADING_SIZE = 16f;
    private static final float BODY_SIZE = 14f;
    private static final float SMALL_SIZE = 12f;

    private final QqBotProperties properties;

    private volatile boolean probed;
    private volatile Fonts fonts;

    public BotFonts(QqBotProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logStatus() {
        if (!properties.isImageEnabled()) {
            log.info("QQ 机器人图片渲染已关闭（MONITOR_BOT_IMAGE_ENABLED=false），长回复按文本截断");
            return;
        }
        Fonts current = fonts();
        if (current == null) {
            log.warn("QQ 机器人图片渲染不可用：没有可用中文字体，长回复将退回截断文本");
        } else if (current.bundled()) {
            log.info("QQ 机器人图片渲染就绪：内置字体 {}", current.base().getFontName());
        } else {
            log.info("QQ 机器人图片渲染就绪：系统字体 {}", current.base().getFontName());
        }
    }

    /** 字体是否就绪。 */
    public boolean available() {
        return fonts() != null;
    }

    /** 当前是否在用内置字体（用于断言「没有悄悄降级」）。 */
    public boolean bundled() {
        Fonts current = fonts();
        return current != null && current.bundled();
    }

    /** 当前生效的字体名，便于排查。 */
    public String name() {
        Fonts current = fonts();
        return current == null ? null : current.base().getFontName();
    }

    public Font title() {
        return fonts().title();
    }

    public Font heading() {
        return fonts().heading();
    }

    public Font body() {
        return fonts().body();
    }

    public Font small() {
        return fonts().small();
    }

    private Fonts fonts() {
        if (!probed) {
            synchronized (this) {
                if (!probed) {
                    fonts = detect();
                    probed = true;
                }
            }
        }
        return fonts;
    }

    private Fonts detect() {
        Font bundled = loadBundled();
        if (bundled != null) {
            return of(bundled, true);
        }

        List<String> candidates = new ArrayList<>();
        String configured = properties.getImageFont();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured.trim());
        }
        candidates.addAll(FONT_CANDIDATES);
        candidates.add(Font.SANS_SERIF);

        for (String name : candidates) {
            Font probe = new Font(name, Font.PLAIN, (int) BODY_SIZE);
            if (probe.canDisplayUpTo(CJK_PROBE) >= 0) {
                continue;
            }
            return of(probe, false);
        }
        return null;
    }

    private Font loadBundled() {
        try (InputStream in = BotFonts.class.getResourceAsStream(BUNDLED_FONT)) {
            if (in == null) {
                log.warn("未找到内置中文字体 {}，改为探测系统字体", BUNDLED_FONT);
                return null;
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            if (font.canDisplayUpTo(CJK_PROBE) >= 0) {
                log.warn("内置字体 {} 缺少所需中文字形，改为探测系统字体", BUNDLED_FONT);
                return null;
            }
            return font;
        } catch (Exception ex) {
            log.warn("加载内置中文字体失败，改为探测系统字体: {}", ex.getMessage());
            return null;
        }
    }

    private static Fonts of(Font base, boolean bundled) {
        return new Fonts(
                base,
                base.deriveFont(Font.BOLD, TITLE_SIZE),
                base.deriveFont(Font.BOLD, HEADING_SIZE),
                base.deriveFont(Font.PLAIN, BODY_SIZE),
                base.deriveFont(Font.PLAIN, SMALL_SIZE),
                bundled);
    }

    private record Fonts(Font base, Font title, Font heading, Font body, Font small, boolean bundled) {
    }
}