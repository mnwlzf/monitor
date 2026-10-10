package com.monitor.platform.bot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把机器人回复渲染成 PNG 图片。
 *
 * <p>渠道状态、账户余额这类内容动辄上百行，QQ 文本消息放不下，会被
 * {@code max-reply-length} 截断；转成图片可以完整呈现。</p>
 *
 * <p><strong>中文字体随 JAR 打包</strong>（{@code /fonts/wqy-microhei.ttc}，Apache-2.0）：
 * JRE 自带字体不含中文，靠系统字体就会「本地能跑、容器里变豆腐块」。
 * 内置字体让本地开发、CI、容器的渲染结果完全一致，且不依赖构建期联网。
 * 万一带内字体缺失，才退回系统字体探测；都不可用时 {@link #renderPng} 返回 null，
 * 由调用方退回截断文本 —— 宁可发截断的文本，也不能发一堆「豆腐块」。</p>
 *
 * <p>用 Java2D 直接绘制，不引第三方渲染库；Spring Boot 默认
 * {@code java.awt.headless=true}，无需显示设备。</p>
 */
@Component
public class BotImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(BotImageRenderer.class);

    /**
     * 随 JAR 打包的中文字体（文泉驿微米黑，Apache-2.0）。
     *
     * <p>用内置字体而不是系统字体：本地开发、CI、容器三处的渲染结果完全一致，
     * 也不依赖构建期联网或镜像里装了字体。许可证与来源见同目录 NOTICE.txt。</p>
     */
    private static final String BUNDLED_FONT = "/fonts/wqy-microhei.ttc";

    /** 用于探测字体是否真的能显示中文。 */
    private static final String CJK_PROBE = "监控号池缓存余额渠道";

    /** 常见中文字体，按优先级探测；都不可用时退回逻辑字体 SansSerif。 */
    private static final List<String> FONT_CANDIDATES = List.of(
            "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "Source Han Sans CN",
            "WenQuanYi Micro Hei", "WenQuanYi Zen Hei",
            "Microsoft YaHei", "微软雅黑", "SimHei", "黑体",
            "PingFang SC", "Hiragino Sans GB");

    private static final int PADDING = 28;
    private static final int ACCENT_WIDTH = 6;
    private static final int TITLE_SIZE = 21;
    private static final int BODY_SIZE = 15;
    private static final int SMALL_SIZE = 12;
    private static final int TITLE_LINE_HEIGHT = 34;
    private static final int BODY_LINE_HEIGHT = 26;
    private static final int BLANK_LINE_HEIGHT = 14;
    private static final int FOOTER_HEIGHT = 34;

    /** 单张图最多渲染的行数，避免超长内容生成过大的图片。 */
    private static final int MAX_LINES = 120;

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color ACCENT = new Color(0x409EFF);
    private static final Color TITLE_COLOR = new Color(0x1F2328);
    private static final Color BODY_COLOR = new Color(0x303133);
    private static final Color MUTED_COLOR = new Color(0x909399);
    private static final Color BORDER_COLOR = new Color(0xE4E7ED);

    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QqBotProperties properties;

    /** 字体探测结果；{@code probed} 为 true 且 {@code fonts} 为 null 表示没有可用中文字体。 */
    private volatile boolean probed;
    private volatile FontSet fonts;

    public BotImageRenderer(QqBotProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时就把字体探测跑一遍并打一行明确结论。
     *
     * <p>探测本身是懒加载的（首次渲染才触发），但那样启动日志里什么都看不到，
     * 排查「图片功能到底能不能用」只能靠猜。这里提前触发，让日志直接给出答案。</p>
     */
    @PostConstruct
    void logFontStatus() {
        if (!properties.isImageEnabled()) {
            log.info("QQ 机器人图片渲染已关闭（MONITOR_BOT_IMAGE_ENABLED=false），长回复按文本截断");
            return;
        }
        FontSet current = fontSet();
        if (current == null) {
            log.warn("QQ 机器人图片渲染不可用：没有可用中文字体，长回复将退回截断文本");
        } else if (current.bundled()) {
            log.info("QQ 机器人图片渲染就绪：内置字体 {}", current.body().getFontName());
        } else {
            log.info("QQ 机器人图片渲染就绪：系统字体 {}", current.body().getFontName());
        }
    }

    /** 当前环境能否渲染中文图片（字体是否就绪）。 */
    public boolean isAvailable() {
        return fontSet() != null;
    }

    /**
     * 当前是否在用随 JAR 打包的内置字体。
     *
     * <p>正常部署下应恒为 true；为 false 说明内置字体缺失，退回了系统字体。
     * 暴露出来是为了让测试能断言「没有悄悄降级」。</p>
     */
    public boolean isUsingBundledFont() {
        FontSet current = fontSet();
        return current != null && current.bundled();
    }

    /** 当前生效的字体名，便于排查渲染问题。 */
    public String fontName() {
        FontSet current = fontSet();
        return current == null ? null : current.body().getFontName();
    }

    /**
     * 把回复渲染成 PNG。
     *
     * @return PNG 字节；未启用、内容为空或没有可用中文字体时返回 {@code null}
     */
    public byte[] renderPng(String text) {
        if (!properties.isImageEnabled() || text == null || text.isBlank()) {
            return null;
        }
        FontSet fontSet = fontSet();
        if (fontSet == null) {
            return null;
        }

        int width = Math.max(360, Math.min(properties.getImageWidth(), 1600));
        int contentWidth = width - PADDING * 2;

        List<RenderLine> lines = layout(text, fontSet, contentWidth);
        int bodyHeight = lines.stream().mapToInt(RenderLine::height).sum();
        int height = PADDING + bodyHeight + FOOTER_HEIGHT;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            configure(g);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);
            // 左侧强调色条，让图片在聊天记录里一眼能认出来
            g.setColor(ACCENT);
            g.fillRect(0, 0, ACCENT_WIDTH, height);

            int y = PADDING;
            for (RenderLine line : lines) {
                if (line.text().isEmpty()) {
                    y += line.height();
                    continue;
                }
                g.setFont(line.font());
                g.setColor(line.color());
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(line.text(), line.x(), y + metrics.getAscent());
                y += line.height();
            }

            g.setFont(fontSet.small());
            g.setColor(MUTED_COLOR);
            FontMetrics footerMetrics = g.getFontMetrics();
            String footer = "Monitor · " + OffsetDateTime.now().format(FOOTER_TIME);
            g.drawString(footer, width - PADDING - footerMetrics.stringWidth(footer),
                    y + (FOOTER_HEIGHT + footerMetrics.getAscent()) / 2);

            g.setColor(BORDER_COLOR);
            g.drawRect(ACCENT_WIDTH, 0, width - ACCENT_WIDTH - 1, height - 1);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.warn("渲染机器人回复图片失败: {}", ex.getMessage());
            return null;
        }
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /**
     * 把纯文本排成若干可绘制的行。
     *
     * <p>约定：首行当标题；以 {@code "- "} 开头的当列表项；其余当正文。这套约定正好匹配
     * {@link MonitorChatTools} 各方法的输出格式。</p>
     */
    private List<RenderLine> layout(String text, FontSet fontSet, int contentWidth) {
        List<RenderLine> lines = new ArrayList<>();
        String[] raw = text.split("\r?\n", -1);

        for (int i = 0; i < raw.length; i++) {
            String line = raw[i].strip();
            if (line.isEmpty()) {
                lines.add(new RenderLine("", fontSet.body(), BODY_COLOR, PADDING, BLANK_LINE_HEIGHT));
                continue;
            }
            if (i == 0) {
                for (String part : wrap(line, metricsFor(fontSet.title()), contentWidth)) {
                    lines.add(new RenderLine(part, fontSet.title(), TITLE_COLOR, PADDING, TITLE_LINE_HEIGHT));
                }
                continue;
            }
            if (line.startsWith("- ")) {
                int indent = PADDING + 14;
                List<String> parts = wrap(line.substring(2).strip(), metricsFor(fontSet.body()),
                        contentWidth - 14);
                for (int j = 0; j < parts.size(); j++) {
                    String prefix = j == 0 ? "• " : "   ";
                    int x = j == 0 ? PADDING : indent;
                    lines.add(new RenderLine(prefix + parts.get(j), fontSet.body(), BODY_COLOR, x, BODY_LINE_HEIGHT));
                }
                continue;
            }
            for (String part : wrap(line, metricsFor(fontSet.body()), contentWidth)) {
                lines.add(new RenderLine(part, fontSet.body(), BODY_COLOR, PADDING, BODY_LINE_HEIGHT));
            }
        }

        if (lines.size() > MAX_LINES) {
            List<RenderLine> limited = new ArrayList<>(lines.subList(0, MAX_LINES));
            limited.add(new RenderLine("…（内容过长，图片已截断）", fontSet.body(), MUTED_COLOR,
                    PADDING, BODY_LINE_HEIGHT));
            return limited;
        }
        return lines;
    }

    /**
     * 按像素宽度折行。
     *
     * <p>中文没有词边界，只能按字断行；但如果断点附近有空格或标点，优先在那里断，
     * 避免英文单词和数字被劈开。</p>
     */
    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> result = new ArrayList<>();
        if (text.isEmpty()) {
            result.add("");
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            current.append(text.charAt(i));
            if (metrics.stringWidth(current.toString()) <= maxWidth || current.length() == 1) {
                continue;
            }
            int breakAt = findBreakPoint(current);
            result.add(current.substring(0, breakAt).stripTrailing());
            current.delete(0, breakAt);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().stripTrailing());
        }
        return result.isEmpty() ? List.of(text) : result;
    }

    /** 在行尾附近找一个更自然的断点（空格 / 常见标点）；找不到就硬断。 */
    private int findBreakPoint(StringBuilder line) {
        int lookback = Math.min(14, line.length() - 1);
        for (int i = line.length() - 1; i >= line.length() - lookback; i--) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t' || c == '，' || c == '。' || c == '、' || c == '；'
                    || c == '：' || c == ',' || c == '.' || c == ';' || c == ':' || c == '）') {
                return i + 1;
            }
        }
        return line.length();
    }

    /** 用一张 1×1 的临时画布取字体度量。 */
    private FontMetrics metricsFor(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(font);
            return g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    /** 首次调用时探测一次字体，结果缓存。 */
    private FontSet fontSet() {
        if (!probed) {
            synchronized (this) {
                if (!probed) {
                    fonts = detectFonts();
                    probed = true;
                }
            }
        }
        return fonts;
    }

    /**
     * 探测可用字体。
     *
     * <p>优先用随 JAR 打包的内置字体；只有它缺失或损坏时才退回系统字体探测，
     * 这样「正常情况下一定渲染得出来」，而不是依赖运行环境装没装字体。</p>
     */
    private FontSet detectFonts() {
        Font bundled = loadBundledFont();
        if (bundled != null) {
            return fontSetOf(bundled, true);
        }

        List<String> candidates = new ArrayList<>();
        String configured = properties.getImageFont();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured.trim());
        }
        candidates.addAll(FONT_CANDIDATES);
        candidates.add(Font.SANS_SERIF);

        for (String name : candidates) {
            Font probe = new Font(name, Font.PLAIN, BODY_SIZE);
            if (probe.canDisplayUpTo(CJK_PROBE) >= 0) {
                continue;
            }
            return fontSetOf(probe, false);
        }

        log.warn("未找到可显示中文的字体，长回复将退回截断文本。"
                + "请确认 JAR 内含 {}，或用 MONITOR_BOT_IMAGE_FONT 指定系统字体名。",
                BUNDLED_FONT);
        return null;
    }

    /** 从 classpath 加载内置中文字体；缺失或损坏时返回 null，由调用方退回系统字体。 */
    private Font loadBundledFont() {
        try (InputStream in = BotImageRenderer.class.getResourceAsStream(BUNDLED_FONT)) {
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

    private FontSet fontSetOf(Font base, boolean bundled) {
        return new FontSet(
                base.deriveFont(Font.BOLD, (float) TITLE_SIZE),
                base.deriveFont(Font.PLAIN, (float) BODY_SIZE),
                base.deriveFont(Font.PLAIN, (float) SMALL_SIZE),
                bundled);
    }

    private record FontSet(Font title, Font body, Font small, boolean bundled) {
    }

    private record RenderLine(String text, Font font, Color color, int x, int height) {
    }
}