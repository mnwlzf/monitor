package com.monitor.platform.bot.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 机器人输出中的邮箱统一脱敏。 */
public final class EmailMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9_])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9_])");

    private EmailMasker() {
    }

    public static BotReport maskReport(BotReport report) {
        return report == null ? null : report.mapText(EmailMasker::maskEmails);
    }

    public static String maskEmails(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = EMAIL.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(maskAddress(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String maskAddress(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        String value = email.trim();
        if (value.length() <= 4) {
            return value.charAt(0) + "***" + value.charAt(value.length() - 1);
        }
        int prefix = value.length() >= 12 ? 3 : 2;
        int suffix = value.length() >= 12 ? 3 : 2;
        int stars = Math.max(3, value.length() - prefix - suffix);
        return value.substring(0, prefix) + "*".repeat(stars) + value.substring(value.length() - suffix);
    }
}
