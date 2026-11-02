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

    /**
     * 判断用户消息里写出的账号标识是否属于当前 QQ 用户。
     *
     * <p>可识别当前绑定邮箱、当前 QQ 号，以及由 QQ 号直接推导出的
     * {@code <QQ号>@qq.com}。用户主动写出自己的账号时，应视为本人查询，
     * 不能因为消息里出现邮箱或 QQ 号就拒绝。</p>
     */
    public static boolean matchesOwner(String candidate, String ownerEmail, Long ownerQq) {
        Optional<String> normalized = normalize(candidate);
        if (normalized.isEmpty()) {
            return false;
        }
        String value = normalized.get();
        Optional<String> owner = normalize(ownerEmail);
        if (owner.isPresent() && owner.get().equals(value)) {
            return true;
        }
        if (ownerQq == null) {
            return false;
        }
        String qq = String.valueOf(ownerQq);
        return value.equals(qq) || value.equals(qq + "@qq.com");
    }
}
