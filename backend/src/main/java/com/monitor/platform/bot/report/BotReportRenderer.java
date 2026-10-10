package com.monitor.platform.bot.report;

import com.monitor.platform.bot.BotFonts;
import com.monitor.platform.bot.QqBotProperties;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link BotReport} 渲染成 PNG。
 *
 * <p>表格与热力图共用一套绘制逻辑：热力图就是「列很窄、单元格只带颜色」的表格，
 * 因此号池色块矩阵和普通数据表走同一条路径，配色也沿用前端 PoolView 的规则
 * （{@code hsl(ratio * 120, 62%, 52%)}，无数据灰色）。</p>
 *
 * <p>中文字体由 {@link BotFonts} 提供（随 JAR 打包）。字体不可用时返回 null，
 * 由调用方退回截断文本 —— 宁可发截断的文本，也不能发一堆「豆腐块」。</p>
 */
@Component
public class BotReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(BotReportRenderer.class);

    private static final int PADDING = 26;
    private static final int ACCENT_WIDTH = 6;
    private static final int CELL_PADDING_X = 10;
    private static final int CELL_PADDING_Y = 7;
    private static final int HEADER_HEIGHT = 34;
    private static final int ROW_HEIGHT = 30;
    private static final int PARAGRAPH_LINE_HEIGHT = 24;
    private static final int BLOCK_GAP = 16;
    private static final int TITLE_GAP = 14;
    private static final int FOOTER_HEIGHT = 30;
    private static final int HEAT_CELL_HEIGHT = 18;
    private static final int HEAT_CELL_INSET = 3;

    /** 图片最大宽度；表格再宽也不会超过它，超过就按比例压缩列宽。 */
    private static final int MAX_IMAGE_WIDTH = 2400;
    private static final int MIN_COLUMN_WIDTH = 44;

    private static final Color BACKGROUND = Color.WHITE;
    private static final Color ACCENT = new Color(0x409EFF);
    private static final Color TITLE_COLOR = new Color(0x1F2328);
    private static final Color HEADING_COLOR = new Color(0x303133);
    private static final Color BODY_COLOR = new Color(0x303133);
    private static final Color MUTED_COLOR = new Color(0x909399);
    private static final Color BORDER_COLOR = new Color(0xE4E7ED);
    private static final Color HEADER_BACKGROUND = new Color(0xF5F7FA);
    private static final Color ZEBRA_BACKGROUND = new Color(0xFAFAFA);
    /** 与前端 PoolView 的 heatNoDataColor 保持一致。 */
    private static final Color HEAT_NO_DATA = new Color(0xE5E9EC);

    /** 文本里的热力标记（emoji 方块）画成色块时的尺寸。 */
    private static final int SWATCH_SIZE = 13;
    private static final int SWATCH_GAP = 2;

    private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 文本里常见的「热度标记」emoji → 实际颜色。
     *
     * <p>大模型很爱用 🟩🟨🟧🟥⬜ 表示热度，但内置中文字体不含 emoji 字形，
     * 直接当文字画会变成空白或豆腐块（图例那一行就是这么坏掉的）。
     * 这里把它们识别出来，画成真正的色块。</p>
     */
    private static final Map<Integer, Color> HEAT_MARKERS = Map.ofEntries(
            Map.entry(0x1F7E9, new Color(0x4CAF50)),   // 🟩 绿
            Map.entry(0x1F7E8, new Color(0xFFEB3B)),   // 🟨 黄
            Map.entry(0x1F7E7, new Color(0xFF9800)),   // 🟧 橙
            Map.entry(0x1F7E5, new Color(0xF44336)),   // 🟥 红
            Map.entry(0x1F7E6, new Color(0x2196F3)),   // 🟦 蓝
            Map.entry(0x1F7EA, new Color(0x9C27B0)),   // 🟪 紫
            Map.entry(0x2B1C, new Color(0xE5E9EC)),    // ⬜ 无数据
            Map.entry(0x2B1B, new Color(0x424242)),    // ⬛ 深色
            Map.entry(0x1F7E2, new Color(0x4CAF50)),   // 🟢
            Map.entry(0x1F7E1, new Color(0xFFEB3B)),   // 🟡
            Map.entry(0x1F7E0, new Color(0xFF9800)),   // 🟠
            Map.entry(0x1F534, new Color(0xF44336)),   // 🔴
            Map.entry(0x26AA, new Color(0xE5E9EC)),    // ⚪
            Map.entry(0x26AB, new Color(0x424242)));   // ⚫

    private final QqBotProperties properties;
    private final BotFonts fonts;

    public BotReportRenderer(QqBotProperties properties, BotFonts fonts) {
        this.properties = properties;
        this.fonts = fonts;
    }

    /** 当前能否渲染图片。 */
    public boolean isAvailable() {
        return properties.isImageEnabled() && fonts.available();
    }

    /**
     * 渲染报表。
     *
     * @return PNG 字节；未启用图片、字体不可用或内容为空时返回 {@code null}
     */
    public byte[] render(BotReport report) {
        if (!isAvailable() || report == null || isEmpty(report)) {
            return null;
        }

        FontMetrics titleMetrics = metrics(fonts.title());
        FontMetrics headingMetrics = metrics(fonts.heading());
        FontMetrics bodyMetrics = metrics(fonts.body());
        FontMetrics smallMetrics = metrics(fonts.small());

        // 先按「内容自然宽度」排版：表格列多时图片就该变宽，而不是把列压到看不清。
        // 只有自然宽度超过硬上限才压缩。
        int hardLimit = MAX_IMAGE_WIDTH - PADDING * 2;
        List<TableLayout> tables = new ArrayList<>();
        for (BotReport.Block block : report.blocks()) {
            tables.add(block.plain() ? null : layoutTable(block, bodyMetrics, hardLimit));
        }

        int contentWidth = titleMetrics.stringWidth(safe(report.title()));
        for (int i = 0; i < tables.size(); i++) {
            TableLayout layout = tables.get(i);
            BotReport.Block block = report.blocks().get(i);
            if (layout != null) {
                contentWidth = Math.max(contentWidth, layout.totalWidth());
            }
            if (block.heading() != null) {
                contentWidth = Math.max(contentWidth, headingMetrics.stringWidth(block.heading()));
            }
        }
        contentWidth = Math.max(contentWidth, 260);

        int imageWidth = Math.min(MAX_IMAGE_WIDTH, Math.max(properties.getImageWidth(), contentWidth + PADDING * 2));
        int textWidth = imageWidth - PADDING * 2;

        if (contentWidth > textWidth) {
            // 自然宽度超出硬上限：按可用宽度重新压一遍列宽
            for (int i = 0; i < tables.size(); i++) {
                BotReport.Block block = report.blocks().get(i);
                if (!block.plain()) {
                    tables.set(i, layoutTable(block, bodyMetrics, textWidth));
                }
            }
        }

        // 段落块需要先折行才能知道高度
        List<List<String>> paragraphs = new ArrayList<>();
        for (BotReport.Block block : report.blocks()) {
            paragraphs.add(block.plain() ? wrapBlock(block, bodyMetrics, textWidth) : List.of());
        }

        int height = PADDING + titleMetrics.getHeight() + TITLE_GAP;
        for (int i = 0; i < report.blocks().size(); i++) {
            BotReport.Block block = report.blocks().get(i);
            if (block.heading() != null && !block.heading().isBlank()) {
                height += headingMetrics.getHeight() + 6;
            }
            TableLayout layout = tables.get(i);
            if (layout != null) {
                height += HEADER_HEIGHT + layout.rows().size() * ROW_HEIGHT;
            } else {
                height += paragraphs.get(i).size() * PARAGRAPH_LINE_HEIGHT;
            }
            if (block.note() != null && !block.note().isBlank()) {
                height += smallMetrics.getHeight() + 4;
            }
            height += BLOCK_GAP;
        }
        for (String note : report.notes()) {
            if (note != null && !note.isBlank()) {
                height += smallMetrics.getHeight() + 2;
            }
        }
        height += FOOTER_HEIGHT;

        BufferedImage image = new BufferedImage(imageWidth, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            configure(g);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, imageWidth, height);
            g.setColor(ACCENT);
            g.fillRect(0, 0, ACCENT_WIDTH, height);

            int y = PADDING;
            if (!safe(report.title()).isBlank()) {
                g.setFont(fonts.title());
                g.setColor(TITLE_COLOR);
                g.drawString(report.title(), PADDING, y + titleMetrics.getAscent());
                y += titleMetrics.getHeight() + TITLE_GAP;
            }

            for (int i = 0; i < report.blocks().size(); i++) {
                BotReport.Block block = report.blocks().get(i);
                if (block.heading() != null && !block.heading().isBlank()) {
                    g.setColor(ACCENT);
                    g.fillRect(PADDING, y + 3, 3, headingMetrics.getHeight() - 6);
                    g.setFont(fonts.heading());
                    g.setColor(HEADING_COLOR);
                    g.drawString(block.heading(), PADDING + 10, y + headingMetrics.getAscent());
                    y += headingMetrics.getHeight() + 6;
                }

                TableLayout layout = tables.get(i);
                if (layout != null) {
                    y = drawTable(g, layout, PADDING, y, bodyMetrics);
                } else {
                    y = drawParagraph(g, paragraphs.get(i), PADDING, y, bodyMetrics);
                }

                if (block.note() != null && !block.note().isBlank()) {
                    g.setFont(fonts.small());
                    g.setColor(MUTED_COLOR);
                    g.drawString(block.note(), PADDING, y + smallMetrics.getAscent());
                    y += smallMetrics.getHeight() + 4;
                }
                y += BLOCK_GAP;
            }

            g.setFont(fonts.small());
            g.setColor(MUTED_COLOR);
            for (String note : report.notes()) {
                if (note == null || note.isBlank()) {
                    continue;
                }
                g.drawString(note, PADDING, y + smallMetrics.getAscent());
                y += smallMetrics.getHeight() + 2;
            }

            String footer = "Monitor · " + OffsetDateTime.now().format(FOOTER_TIME);
            g.drawString(footer, imageWidth - PADDING - smallMetrics.stringWidth(footer),
                    height - PADDING + smallMetrics.getAscent() - 8);

            g.setColor(BORDER_COLOR);
            g.drawRect(ACCENT_WIDTH, 0, imageWidth - ACCENT_WIDTH - 1, height - 1);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.warn("渲染机器人报表图片失败: {}", ex.getMessage());
            return null;
        }
    }

    private int drawTable(Graphics2D g, TableLayout layout, int x, int y, FontMetrics bodyMetrics) {
        int[] widths = layout.widths();
        int total = layout.totalWidth();

        // 表头
        g.setColor(HEADER_BACKGROUND);
        g.fillRect(x, y, total, HEADER_HEIGHT);
        g.setFont(fonts.heading().deriveFont((float) fonts.body().getSize()));
        g.setColor(MUTED_COLOR);
        int cx = x;
        for (int c = 0; c < widths.length; c++) {
            String header = layout.cols().get(c).header();
            if (header != null && !header.isBlank()) {
                FontMetrics fm = g.getFontMetrics();
                String text = ellipsize(header, fm, widths[c] - CELL_PADDING_X * 2);
                int textX = layout.cols().get(c).align() == BotReport.Align.RIGHT
                        ? cx + widths[c] - CELL_PADDING_X - fm.stringWidth(text)
                        : cx + CELL_PADDING_X;
                g.drawString(text, textX, y + (HEADER_HEIGHT + fm.getAscent()) / 2 - 2);
            }
            cx += widths[c];
        }
        g.setColor(BORDER_COLOR);
        g.drawLine(x, y + HEADER_HEIGHT, x + total, y + HEADER_HEIGHT);
        y += HEADER_HEIGHT;

        // 数据行
        for (int r = 0; r < layout.rows().size(); r++) {
            if (r % 2 == 1) {
                g.setColor(ZEBRA_BACKGROUND);
                g.fillRect(x, y, total, ROW_HEIGHT);
            }
            g.setFont(fonts.body());
            int cellX = x;
            List<BotReport.Cell> cells = layout.rows().get(r).cells();
            for (int c = 0; c < widths.length; c++) {
                BotReport.Cell cell = c < cells.size() ? cells.get(c) : null;
                if (cell != null && cell.heat() != null) {
                    drawHeat(g, cellX + HEAT_CELL_INSET, y + (ROW_HEIGHT - HEAT_CELL_HEIGHT) / 2,
                            widths[c] - HEAT_CELL_INSET * 2, HEAT_CELL_HEIGHT, cell.heat());
                } else if (cell != null && cell.text() != null && !cell.text().isBlank()) {
                    FontMetrics fm = g.getFontMetrics();
                    List<Run> runs = splitRuns(ellipsize(cell.text(), fm, widths[c] - CELL_PADDING_X * 2));
                    int runWidth = runsWidth(runs, fm);
                    int textX = layout.cols().get(c).align() == BotReport.Align.RIGHT
                            ? cellX + widths[c] - CELL_PADDING_X - runWidth
                            : cellX + CELL_PADDING_X;
                    drawRuns(g, runs, textX, y + (ROW_HEIGHT + fm.getAscent()) / 2 - 2, fm);
                }
                cellX += widths[c];
            }
            g.setColor(BORDER_COLOR);
            g.drawLine(x, y + ROW_HEIGHT, x + total, y + ROW_HEIGHT);
            y += ROW_HEIGHT;
        }
        return y;
    }

    private int drawParagraph(Graphics2D g, List<String> lines, int x, int y, FontMetrics bodyMetrics) {
        g.setFont(fonts.body());
        for (String line : lines) {
            drawRuns(g, splitRuns(line), x, y + bodyMetrics.getAscent(), bodyMetrics);
            y += PARAGRAPH_LINE_HEIGHT;
        }
        return y;
    }

    /**
     * 按顺序画一段文本：普通字符照常画，热力标记画成色块。
     *
     * <p>色块在垂直方向与文字居中对齐，这样「🟩 ≥90%」这种图例读起来才自然。</p>
     */
    private void drawRuns(Graphics2D g, List<Run> runs, int x, int baseline, FontMetrics metrics) {
        int cursor = x;
        for (Run run : runs) {
            if (run.swatch() != null) {
                g.setColor(run.swatch());
                int top = baseline - metrics.getAscent() + Math.max(0, (metrics.getAscent() - SWATCH_SIZE) / 2);
                g.fillRoundRect(cursor, top, SWATCH_SIZE, SWATCH_SIZE, 3, 3);
                cursor += SWATCH_SIZE + SWATCH_GAP;
            } else {
                g.setColor(BODY_COLOR);
                g.drawString(run.text(), cursor, baseline);
                cursor += metrics.stringWidth(run.text());
            }
        }
    }

    /** 把文本切成「普通文字」与「热力标记」两种片段。 */
    private static List<Run> splitRuns(String text) {
        List<Run> runs = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Color swatch = HEAT_MARKERS.get(codePoint);
            if (swatch == null) {
                plain.appendCodePoint(codePoint);
            } else {
                if (!plain.isEmpty()) {
                    runs.add(new Run(plain.toString(), null));
                    plain.setLength(0);
                }
                runs.add(new Run(null, swatch));
            }
            i += Character.charCount(codePoint);
        }
        if (!plain.isEmpty()) {
            runs.add(new Run(plain.toString(), null));
        }
        return runs;
    }

    /** 一段文本的显示宽度：热力标记按色块宽度算，其余按字体宽度算。 */
    private static int textWidth(String text, FontMetrics metrics) {
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (HEAT_MARKERS.containsKey(codePoint)) {
                total += SWATCH_SIZE + SWATCH_GAP;
            } else {
                total += metrics.stringWidth(new String(Character.toChars(codePoint)));
            }
            i += Character.charCount(codePoint);
        }
        return total;
    }

    private static int runsWidth(List<Run> runs, FontMetrics metrics) {
        int total = 0;
        for (Run run : runs) {
            total += run.swatch() != null ? SWATCH_SIZE + SWATCH_GAP : metrics.stringWidth(run.text());
        }
        return total;
    }

    /** 文本片段：要么是普通文字，要么是一个色块。 */
    private record Run(String text, Color swatch) {
    }

    /** 按热力值画一个圆角色块；null 表示无数据，画灰色。 */
    private void drawHeat(Graphics2D g, int x, int y, int width, int height, Double ratio) {
        g.setColor(heatColor(ratio));
        g.fillRoundRect(x, y, width, height, 6, 6);
    }

    /**
     * 热力配色，与前端 PoolView 一致：{@code hsl(ratio * 120, 62%, 52%)}，
     * 无数据用灰色（不用 0 冒充）。
     */
    static Color heatColor(Double ratio) {
        if (ratio == null || !Double.isFinite(ratio)) {
            return HEAT_NO_DATA;
        }
        double clamped = Math.max(0, Math.min(1, ratio));
        return hsl(clamped * 120.0, 0.62, 0.52);
    }

    private static Color hsl(double hueDegrees, double saturation, double lightness) {
        double c = (1 - Math.abs(2 * lightness - 1)) * saturation;
        double h = hueDegrees / 60.0;
        double x = c * (1 - Math.abs(h % 2 - 1));
        double r;
        double g;
        double b;
        if (h < 1) {
            r = c; g = x; b = 0;
        } else if (h < 2) {
            r = x; g = c; b = 0;
        } else if (h < 3) {
            r = 0; g = c; b = x;
        } else if (h < 4) {
            r = 0; g = x; b = c;
        } else if (h < 5) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        double m = lightness - c / 2;
        return new Color(
                (int) Math.round(Math.max(0, Math.min(1, r + m)) * 255),
                (int) Math.round(Math.max(0, Math.min(1, g + m)) * 255),
                (int) Math.round(Math.max(0, Math.min(1, b + m)) * 255));
    }

    /** 计算每一列的宽度：取表头与各单元格的最大文本宽度，再夹到最小宽度。 */
    private TableLayout layoutTable(BotReport.Block block, FontMetrics bodyMetrics, int available) {
        FontMetrics headerMetrics = metrics(fonts.heading().deriveFont((float) fonts.body().getSize()));
        int columnCount = block.cols().size();
        int[] widths = new int[columnCount];

        for (int c = 0; c < columnCount; c++) {
            BotReport.Col col = block.cols().get(c);
            int width = col.header() == null ? 0 : headerMetrics.stringWidth(col.header()) + CELL_PADDING_X * 2;
            for (BotReport.Row row : block.rows()) {
                if (c >= row.cells().size()) {
                    continue;
                }
                BotReport.Cell cell = row.cells().get(c);
                if (cell.heat() != null) {
                    width = Math.max(width, col.minWidth());
                } else if (cell.text() != null) {
                    width = Math.max(width, bodyMetrics.stringWidth(cell.text()) + CELL_PADDING_X * 2);
                }
            }
            widths[c] = Math.max(Math.max(width, col.minWidth()), MIN_COLUMN_WIDTH);
        }

        int total = 0;
        for (int width : widths) {
            total += width;
        }
        if (total > available && total > 0) {
            // 超宽按比例压缩，但不小于最小列宽
            double factor = (double) available / total;
            total = 0;
            for (int c = 0; c < columnCount; c++) {
                widths[c] = Math.max(MIN_COLUMN_WIDTH, (int) Math.floor(widths[c] * factor));
                total += widths[c];
            }
        }
        return new TableLayout(block.cols(), block.rows(), widths);
    }

    private List<String> wrapBlock(BotReport.Block block, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (BotReport.Row row : block.rows()) {
            String text = row.cells().isEmpty() ? "" : row.cells().get(0).text();
            if (text == null || text.isEmpty()) {
                lines.add("");
                continue;
            }
            lines.addAll(wrap(text, metrics, maxWidth));
        }
        return lines;
    }

    /** 按像素宽度折行；断点优先落在空格或标点，避免英文单词与数字被劈开。 */
    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> result = new ArrayList<>();
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

    private String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (maxWidth <= 0 || metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private FontMetrics metrics(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(font);
            return g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static boolean isEmpty(BotReport report) {
        if (report.blocks() != null && !report.blocks().isEmpty()) {
            return false;
        }
        return safe(report.title()).isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record TableLayout(List<BotReport.Col> cols, List<BotReport.Row> rows, int[] widths) {

        int totalWidth() {
            int total = 0;
            for (int width : widths) {
                total += width;
            }
            return total;
        }
    }
}