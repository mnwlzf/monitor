package com.monitor.platform.bot.identity;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮箱的统一处理：规范化、格式校验、提取 QQ 号。
 *
 * <p>身份识别的所有环节（用户缓存、绑定、自定义管理员）都必须用同一套规则，
 * 否则会出现「缓存里是小写、绑定里是原始大小写」这类对不上的问题。</p>
 */
public final class Emails {

    /** 够用即可，不做 RFC 级校验。 */
    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** 形如 {@code 123456@qq.com} 的邮箱，本地部分即 QQ 号。 */
    private static final Pattern QQ_LOCAL_PART = Pattern.compile("^(\\d{5,12})@");

    private Emails() {
    }

    /** 去空格 + 转小写；空串按「无值」处理。 */
    public static Optional<String> normalize(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /** 是否为合法邮箱格式。 */
    public static boolean isValid(String email) {
        return normalize(email).map(value -> FORMAT.matcher(value).matches()).orElse(false);
    }

    /** 从邮箱里提取 QQ 号；不是 {@code <QQ号>@...} 形式时返回空。 */
    public static Optional<String> qqLocalPart(String email) {
        return normalize(email).flatMap(value -> {
            Matcher matcher = QQ_LOCAL_PART.matcher(value);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        });
    }
}