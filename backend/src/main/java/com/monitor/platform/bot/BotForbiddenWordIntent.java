package com.monitor.platform.bot;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理员维护违禁词的自然语言识别。
 *
 * <p>只识别意图明确的短句并转换成确定性管理命令；无法确认时返回空，
 * 继续走普通消息流程，避免误删或误加词条。</p>
 */
final class BotForbiddenWordIntent {

    private static final String LEAD = "(?:请|帮我|麻烦你?|我想)?\\s*";
    private static final String QUOTE = "[“”\"'‘’]";

    private static final Pattern VIEW = Pattern.compile(
            "^" + LEAD + "(?:查看|看看|列出|显示|查询)?(?:一下)?(?:现有|已有|当前)?"
                    + "违禁词(?:列表|清单)?(?:有哪些|有什么|是哪些)?[。！!？?]?$");

    private static final Pattern ADD_SUFFIX = Pattern.compile(
            "^" + LEAD + "(?:添加|新增|增加|加入|设置|设为|列为)(?:一个|一条)?(?:新的)?"
                    + "违禁词(?:词条)?[\\s:：]+(.+?)[。！!？?]?$");

    private static final Pattern ADD_PREFIX = Pattern.compile(
            "^" + LEAD + "(?:把|将)\\s*" + QUOTE + "?(.+?)" + QUOTE + "?"
                    + "\\s*(?:加入|添加到|添加至|设为|设置为|列为)(?:违禁词|违禁词列表)[。！!？?]?$");

    private static final Pattern DELETE_SUFFIX = Pattern.compile(
            "^" + LEAD + "(?:删除|删掉|移除|去掉|去除|删了)(?:一个|一条)?"
                    + "违禁词(?:词条)?[\\s:：]+(.+?)[。！!？?]?$");

    private static final Pattern DELETE_PREFIX = Pattern.compile(
            "^" + LEAD + "(?:把|将)\\s*" + QUOTE + "?(.+?)" + QUOTE + "?"
                    + "\\s*从(?:违禁词|违禁词列表)(?:中|里)?\\s*"
                    + "(?:删除|删掉|移除|去掉|去除|删了)[。！!？?]?$");

    private static final Pattern VIEW_EN = Pattern.compile(
            "^(?:show|list|view)\\s+(?:forbidden|banned)\\s+words?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ADD_EN = Pattern.compile(
            "^(?:add|create)\\s+(?:forbidden|banned)\\s+word\\s*[:：]?\\s*(.+?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DELETE_EN = Pattern.compile(
            "^(?:remove|delete)\\s+(?:forbidden|banned)\\s+word\\s*[:：]?\\s*(.+?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private BotForbiddenWordIntent() {
    }

    /** 返回等价的确定性命令；不是明确的管理意图时返回空。 */
    static Optional<String> toCommandLine(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        if (VIEW.matcher(normalized).matches() || VIEW_EN.matcher(normalized).matches()) {
            return Optional.of("违禁词");
        }
        Optional<String> add = capture(ADD_SUFFIX, normalized)
                .or(() -> capture(ADD_PREFIX, normalized))
                .or(() -> capture(ADD_EN, normalized));
        if (add.isPresent()) {
            return add.map(word -> "添加违禁词 " + word);
        }
        Optional<String> delete = capture(DELETE_SUFFIX, normalized)
                .or(() -> capture(DELETE_PREFIX, normalized))
                .or(() -> capture(DELETE_EN, normalized));
        return delete.map(word -> "删除违禁词 " + word);
    }

    private static Optional<String> capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String word = cleanWord(matcher.group(1));
        return word.isBlank() ? Optional.empty() : Optional.of(word);
    }

    private static String cleanWord(String raw) {
        String word = raw.strip();
        if (word.length() >= 2 && isOpeningQuote(word.charAt(0)) && isClosingQuote(word.charAt(word.length() - 1))) {
            word = word.substring(1, word.length() - 1).strip();
        }
        return word.replaceFirst("[。！!？?；;]+$", "").strip();
    }

    private static boolean isOpeningQuote(char value) {
        return value == '“' || value == '"' || value == '\'' || value == '‘';
    }

    private static boolean isClosingQuote(char value) {
        return value == '”' || value == '"' || value == '\'' || value == '’';
    }
}
