package com.monitor.platform.bot.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人可渲染的结构化报表。
 *
 * <p>之所以不直接渲染纯文本：渠道状态、账户余额这类内容本质是表格，用项目符号列出来
 * 既对不齐也不好扫读。这里把「要展示什么」和「怎么画」分开 ——
 * {@link #toPlainText()} 用于文本兜底与大模型工具返回值，{@code BotReportRenderer}
 * 负责画成图片。</p>
 *
 * <p>两种内容块：{@link Block#cols()} 非空是表格；为空是段落（纯文字，不画表头与网格）。
 * 热力图不单独建模 —— 它就是「若干列很窄、单元格只带颜色」的表格，
 * 因此一个渲染器就能同时覆盖普通表格、号池色块矩阵和纯文本。</p>
 *
 * @param title  标题
 * @param blocks 内容块，按顺序渲染
 * @param notes  底部提示
 */
public record BotReport(String title, List<Block> blocks, List<String> notes) {

    /** 对齐方式。数字列右对齐更好扫读。 */
    public enum Align {
        LEFT, RIGHT
    }

    /**
     * 列定义。
     *
     * @param header   表头
     * @param align    对齐方式
     * @param minWidth 最小列宽（像素）；热力列给一个很小的值即可
     */
    public record Col(String header, Align align, int minWidth, boolean heat) {

        public static Col left(String header) {
            return new Col(header, Align.LEFT, 0, false);
        }

        public static Col right(String header) {
            return new Col(header, Align.RIGHT, 0, false);
        }

        /**
         * 热力色块列：只画颜色，不写字。
         *
         * <p>{@code heat=true} 的列宽度由渲染器统一决定，<strong>不看表头文字</strong> ——
         * 表头是「10-09 15:00」这种时间戳，按它算宽度会把列撑到 90px，
         * 色块缩成中间一个小点，整张图看着又稀疏又淡。前端也是统一列宽、标签溢出。</p>
         */
        public static Col heat(String header) {
            return new Col(header, Align.LEFT, 0, true);
        }
    }

    /**
     * 单元格。
     *
     * @param text 文本，可为空（纯色块）
     * @param heat 热力值 0~1；非 null 时按热力着色（null 表示普通单元格）
     */
    public record Cell(String text, Double heat) {

        public static Cell of(String text) {
            return new Cell(text, null);
        }

        public static Cell heat(Double ratio) {
            return new Cell("", ratio);
        }
    }

    /** 一行数据。 */
    public record Row(List<Cell> cells) {

        public static Row of(String... values) {
            List<Cell> cells = new ArrayList<>(values.length);
            for (String value : values) {
                cells.add(Cell.of(value));
            }
            return new Row(cells);
        }
    }

    /**
     * 一个内容块。
     *
     * @param heading 分节标题，可为 null
     * @param cols    列定义；为空表示这是段落块（不画表头与网格）
     * @param rows    数据行
     * @param note    该节底注，可为 null
     */
    public record Block(String heading, List<Col> cols, List<Row> rows, String note) {

        public boolean plain() {
            return cols == null || cols.isEmpty();
        }
    }

    public static BotReport of(String title, Block... blocks) {
        return new BotReport(title, List.of(blocks), List.of());
    }

    public static BotReport of(String title, List<Block> blocks, List<String> notes) {
        return new BotReport(title, blocks, notes);
    }

    /** 构造一个段落块（每行一个字符串）。 */
    public static Block paragraph(String heading, List<String> lines) {
        List<Row> rows = new ArrayList<>(lines.size());
        for (String line : lines) {
            rows.add(new Row(List.of(Cell.of(line))));
        }
        return new Block(heading, List.of(), rows, null);
    }

    /**
     * 把大模型的 Markdown 回答解析成报表。
     *
     * <p>大模型没法直接产出 {@link Block}，但它会写 Markdown：这里识别
     * {@code | a | b |} 表格、{@code #} 标题与普通段落。这样自然语言问答里的长列表
     * 也能落成表格图，而不是一长串项目符号。</p>
     *
     * <p>解析不出表格时退化成纯段落块，渲染结果与旧的纯文本图一致。</p>
     */
    public static BotReport fromMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new BotReport("", List.of(), List.of());
        }

        String title = null;
        List<Block> blocks = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        String pendingHeading = null;

        // 当前正在收集的表格
        List<String> tableHeaders = null;
        List<Row> tableRows = new ArrayList<>();

        for (String rawLine : markdown.split("\r?\n", -1)) {
            String line = rawLine.strip();

            if (line.startsWith("|") && line.endsWith("|") && line.length() > 2) {
                List<String> cells = splitTableRow(line);
                if (isSeparatorRow(cells)) {
                    continue;
                }
                if (tableHeaders == null) {
                    tableHeaders = cells;
                } else {
                    tableRows.add(new Row(toCells(cells)));
                }
                continue;
            }

            // 表格结束
            if (tableHeaders != null) {
                blocks.add(tableBlock(pendingHeading, tableHeaders, tableRows));
                pendingHeading = null;
                tableHeaders = null;
                tableRows = new ArrayList<>();
            }

            if (line.isEmpty()) {
                flushParagraph(blocks, paragraphs);
                continue;
            }

            int hashes = leadingHashes(line);
            if (hashes > 0) {
                flushParagraph(blocks, paragraphs);
                String text = line.substring(hashes).strip();
                if (title == null && hashes == 1) {
                    title = text;
                } else {
                    pendingHeading = text;
                }
                continue;
            }

            paragraphs.add(stripInlineMarkdown(line));
        }

        if (tableHeaders != null) {
            blocks.add(tableBlock(pendingHeading, tableHeaders, tableRows));
        }
        flushParagraph(blocks, paragraphs);

        return new BotReport(title == null ? "" : title, blocks, List.of());
    }

    private static void flushParagraph(List<Block> blocks, List<String> paragraphs) {
        if (!paragraphs.isEmpty()) {
            blocks.add(paragraph(null, new ArrayList<>(paragraphs)));
            paragraphs.clear();
        }
    }

    private static Block tableBlock(String heading, List<String> headers, List<Row> rows) {
        List<Col> cols = new ArrayList<>(headers.size());
        for (String header : headers) {
            // 表头里带数字/百分号的多半是数值列，右对齐
            boolean numeric = header.matches(".*(率|数|TOKEN|Token|token|ms|s$|耗时|请求|余额|额度).*");
            cols.add(new Col(header, numeric ? Align.RIGHT : Align.LEFT, 0, false));
        }
        return new Block(heading, cols, rows, null);
    }

    private static List<Cell> toCells(List<String> values) {
        List<Cell> cells = new ArrayList<>(values.size());
        for (String value : values) {
            cells.add(Cell.of(value));
        }
        return cells;
    }

    private static List<String> splitTableRow(String line) {
        String body = line.substring(1, line.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String part : body.split("\\|", -1)) {
            cells.add(part.strip());
        }
        return cells;
    }

    /** Markdown 表格的分隔行：|---|---| 或 |:--:|。 */
    private static boolean isSeparatorRow(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches(":?-{2,}:?")) {
                return false;
            }
        }
        return !cells.isEmpty();
    }

    private static int leadingHashes(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return count > 0 && count < line.length() && line.charAt(count) == ' ' ? count : 0;
    }

    /**
     * 去掉 **加粗**、`代码` 这类行内标记，图片里不需要它们。
     *
     * <p>刻意保留行首的 {@code "- "}：文本兜底走的是同一份内容，
     * 换成「•」会让 QQ 里看到的纯文本和以前不一样。</p>
     */
    private static String stripInlineMarkdown(String line) {
        return line.replace("**", "").replace("`", "");
    }

    /**
     * 转成纯文本：文本兜底、以及给大模型看的工具返回值都用它。
     */
    public String toPlainText() {
        List<String> lines = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            lines.add(title);
        }
        for (Block block : blocks) {
            if (block.heading() != null && !block.heading().isBlank()) {
                lines.add(block.heading());
            }
            for (Row row : block.rows()) {
                if (block.plain()) {
                    String text = row.cells().isEmpty() ? null : row.cells().get(0).text();
                    if (text != null) {
                        lines.add(text);
                    }
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < row.cells().size(); i++) {
                    String text = row.cells().get(i).text();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    String header = i < block.cols().size() ? block.cols().get(i).header() : null;
                    parts.add(header == null || header.isBlank() ? text : header + " " + text);
                }
                lines.add("- " + String.join("，", parts));
            }
            if (block.note() != null && !block.note().isBlank()) {
                lines.add(block.note());
            }
        }
        for (String note : notes) {
            if (note != null && !note.isBlank()) {
                lines.add(note);
            }
        }
        // 用 join 而不是逐段 append：没有标题时不会多出一个前导换行
        return String.join("\n", lines);
    }
}