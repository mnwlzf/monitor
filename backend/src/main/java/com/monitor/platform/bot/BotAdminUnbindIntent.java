package com.monitor.platform.bot;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理员解绑邮箱的自然语言与确认格式。 */
final class BotAdminUnbindIntent {

    enum Action { REQUEST, CONFIRM }

    record Intent(Action action, Long targetQq, String email, String code) {
    }

    private static final String LEAD = "(?:请|帮我|麻烦你?)?\\s*";
    private static final String EMAIL = "([^\\s@]+@[^\\s@]+\\.[^\\s@]+)";

    private static final Pattern REQUEST = Pattern.compile(
            "^" + LEAD + "用户\\s+(\\d{5,12})\\s+邮箱\\s+" + EMAIL + "\\s+解除绑定[。！!？?]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern COMMAND = Pattern.compile(
            "^(?:解绑|解除绑定)\\s+(\\d{5,12})\\s+" + EMAIL + "[。！!？?]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CONFIRM = Pattern.compile(
            "^" + LEAD + "确认解绑\\s+([A-Za-z0-9]{6})[。！!？?]?$");

    private BotAdminUnbindIntent() {
    }

    static Optional<Intent> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        Matcher request = REQUEST.matcher(normalized);
        if (request.matches()) {
            return Optional.of(new Intent(Action.REQUEST, Long.parseLong(request.group(1)),
                    request.group(2), null));
        }
        Matcher command = COMMAND.matcher(normalized);
        if (command.matches()) {
            return Optional.of(new Intent(Action.REQUEST, Long.parseLong(command.group(1)),
                    command.group(2), null));
        }
        Matcher confirm = CONFIRM.matcher(normalized);
        if (confirm.matches()) {
            return Optional.of(new Intent(Action.CONFIRM, null, null, confirm.group(1).toUpperCase()));
        }
        return Optional.empty();
    }

    static boolean looksLikeUnbind(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.strip();
        return normalized.contains("解除绑定")
                || normalized.startsWith("解绑")
                || normalized.startsWith("确认解绑");
    }
}
