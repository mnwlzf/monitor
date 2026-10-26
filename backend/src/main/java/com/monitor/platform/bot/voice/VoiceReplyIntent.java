package com.monitor.platform.bot.voice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户文字里识别「明确要求语音回复」以及可选的音色/语气。
 *
 * <p>只有明确指令才触发语音，普通聊天不会自动发语音。支持的说法包括：
 * 「语音回复」「发条语音」「用温柔的语气语音回复」「用 alex 音色语音回复」。
 * 「不要语音回复」「别发语音」等否定表达优先，不会被误判。</p>
 */
public record VoiceReplyIntent(boolean requested, String voice, String style, String cleanedText) {

    private static final Pattern NEGATIVE = Pattern.compile(
            "(?:不要|别|不用|无需|不需要|禁止)\\s*(?:用|发|回)?\\s*(?:语音|声音)(?:回复|回答)?",
            Pattern.CASE_INSENSITIVE);

    /** 带风格/音色的完整指令。 */
    private static final Pattern STYLE_DIRECTIVE = Pattern.compile(
            "(?:请|麻烦)?\\s*(?:你)?\\s*(?:用|以)\\s*([^，。！？,!?；;\\n]{1,30}?)\\s*(?:的)?\\s*"
                    + "(?:语气|声音|音色|风格)?\\s*(?:来|给我|为我)?\\s*"
                    + "(?:语音回复|语音回答|声音回复|声音回答|回复语音|回答语音|发语音|回语音|语音说|回复|回答)"
                    + "(?:我|一下|一条)?(?:语音|声音)?",
            Pattern.CASE_INSENSITIVE);

    /** 不带风格的简单语音指令。 */
    private static final Pattern SIMPLE_DIRECTIVE = Pattern.compile(
            "(?:请|麻烦)?\\s*(?:用)?\\s*(?:(?:语音|声音)(?:回复|回答|说)"
                    + "|发(?:一?条)?语音"
                    + "|回(?:复)?(?:我)?(?:一?条)?语音)"
                    + "(?:我|一下|一条)?",
            Pattern.CASE_INSENSITIVE);

    /** 指令里可能把风格放在别处，例如「语音回复，用温柔的语气」。 */
    private static final Pattern STYLE_ONLY = Pattern.compile(
            "(?:用|以)\\s*([^，。！？,!?；;\\n]{1,30}?)\\s*(?:的)?\\s*(?:语气|声音|音色|风格)",
            Pattern.CASE_INSENSITIVE);

    public static VoiceReplyIntent parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) {
            return none("");
        }
        if (NEGATIVE.matcher(text).find()) {
            return none(text);
        }

        Matcher directive = STYLE_DIRECTIVE.matcher(text);
        boolean requested = directive.find();
        String style = requested ? normalizeStyle(directive.group(1)) : null;
        int start = requested ? directive.start() : -1;
        int end = requested ? directive.end() : -1;

        if (!requested) {
            Matcher simple = SIMPLE_DIRECTIVE.matcher(text);
            if (simple.find()) {
                requested = true;
                start = simple.start();
                end = simple.end();
            }
        }
        if (!requested) {
            return none(text);
        }

        if (style == null || style.isBlank()) {
            Matcher styleOnly = STYLE_ONLY.matcher(text);
            if (styleOnly.find()) {
                style = normalizeStyle(styleOnly.group(1));
            }
        }

        String voice = extractVoice(style);
        if (voice != null) {
            style = null;
        }
        return new VoiceReplyIntent(true, voice, blankToNull(style), removeDirective(text, start, end));
    }

    private static VoiceReplyIntent none(String text) {
        return new VoiceReplyIntent(false, null, null, text);
    }

    private static String removeDirective(String text, int start, int end) {
        if (start < 0 || end <= start) {
            return text;
        }
        String cleaned = (text.substring(0, start) + " " + text.substring(end))
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = cleaned.replaceAll("^[，,。；;、\\s]+", "")
                .replaceAll("[，,。；;、\\s]+$", "")
                .trim();
        return cleaned;
    }

    private static String normalizeStyle(String value) {
        if (value == null) {
            return null;
        }
        String style = value.trim()
                .replaceAll("^[\"'“”‘’《》]+|[\"'“”‘’《》]+$", "")
                .replaceAll("(?:的)?(?:语气|声音|音色|风格)$", "")
                .trim();
        if (style.length() > 80) {
            style = style.substring(0, 80).trim();
        }
        return style;
    }

    /**
     * 把「用 alex 音色」「用 fnlp/MOSS-TTSD-v0.5:alex 音色」里的音色名识别出来。
     * 中文的「温柔 / 活泼」等属于语气描述，交给大模型组织措辞，不当作音色 ID。
     */
    private static String extractVoice(String style) {
        if (style == null || style.isBlank()) {
            return null;
        }
        String value = style.trim();
        return value.matches("[A-Za-z0-9][A-Za-z0-9._/:\\-]{0,79}") ? value : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}