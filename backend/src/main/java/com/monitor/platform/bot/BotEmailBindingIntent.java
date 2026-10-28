package com.monitor.platform.bot;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 邮箱绑定、验证和解绑的自然语言意图识别。 */
final class BotEmailBindingIntent {

    enum Action { BIND, VERIFY, UNBIND }

    record Intent(Action action, String value) {
    }

    private static final String LEAD = "(?:请|帮我|麻烦你?|我要|我想|需要)?\\s*";
    private static final String EMAIL = "([^\\s@]+@[^\\s@]+\\.[^\\s@]+)";

    private static final Pattern BIND_SUFFIX = Pattern.compile(
            "^" + LEAD + "(?:绑定|关联)(?:我的)?(?:QQ|账号)?(?:邮箱)?\\s*[:：]?\\s*"
                    + EMAIL + "[。！!？?]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern BIND_PREFIX = Pattern.compile(
            "^" + LEAD + "(?:把|将)?\\s*" + EMAIL
                    + "\\s*(?:绑定|关联)(?:到|至)?(?:我的)?(?:QQ|账号)?(?:邮箱)?[。！!？?]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern BIND_EN = Pattern.compile(
            "^(?:please\\s+)?(?:bind|link)\\s+(?:my\\s+)?email\\s*[:：]?\\s*"
                    + EMAIL + "$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern VERIFY = Pattern.compile(
            "^" + LEAD + "(?:验证码|验证|校验)(?:我的)?(?:邮箱)?\\s*[:：]?\\s*(\\d{6})$");

    private static final Pattern UNBIND = Pattern.compile(
            "^" + LEAD + "(?:解绑|解除绑定)(?:我的)?(?:QQ)?(?:邮箱)?[。！!？?]?$");

    private BotEmailBindingIntent() {
    }

    static Optional<Intent> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        Optional<String> bind = capture(BIND_SUFFIX, normalized)
                .or(() -> capture(BIND_PREFIX, normalized))
                .or(() -> capture(BIND_EN, normalized));
        if (bind.isPresent()) {
            return Optional.of(new Intent(Action.BIND, bind.get()));
        }
        Optional<String> verify = capture(VERIFY, normalized);
        if (verify.isPresent()) {
            return Optional.of(new Intent(Action.VERIFY, verify.get()));
        }
        if (UNBIND.matcher(normalized).matches()) {
            return Optional.of(new Intent(Action.UNBIND, ""));
        }
        return Optional.empty();
    }

    private static Optional<String> capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.matches() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }
}
