package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.common.time.BusinessTime;
import org.springframework.web.util.HtmlUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 邮件正文的通用格式化。
 *
 * <p>邮件正文是 HTML，取值一律要转义；时间统一按 {@link BusinessTime#ZONE} 展示。
 * 这些方法以前在多个告警服务里各写一份，集中到这里统一口径。</p>
 */
public final class MailFormat {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MailFormat() {
    }

    /** HTML 转义；{@code null} 视为空串。 */
    public static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    /** 邮件展示时间；{@code null} 显示为破折号。 */
    public static String formatTime(OffsetDateTime time) {
        return time == null ? "—" : TIME_FORMATTER.format(time.atZoneSameInstant(BusinessTime.ZONE));
    }

    /**
     * 变更事件里存的是 JSON 文本，字符串会带引号，这里去掉引号便于阅读。
     *
     * @return 去引号后的文本；空值 / {@code null} / {@code "null"} 返回 {@code null}
     */
    public static String scalar(String jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        String text = jsonValue.trim();
        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 账号展示名：优先备注名，其次用户名，最后回退到「账号 + ID」。 */
    public static String displayName(AccountEntity account) {
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return "账号 " + account.getId();
    }
}
