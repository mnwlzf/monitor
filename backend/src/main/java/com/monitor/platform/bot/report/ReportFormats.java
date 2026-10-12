package com.monitor.platform.bot.report;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 报表里的通用格式化：金额、百分比、耗时、时间。
 *
 * <p>空值统一显示 {@code -}，避免图片表格里出现 null；不同报表共用一份口径，
 * 免得同一个数字在「号池」和「用量」里长得不一样。</p>
 */
public final class ReportFormats {

    private ReportFormats() {
    }

    /** 金额 / 额度：去掉无意义的尾零。 */
    public static String decimal(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    public static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** 首 Token 用秒更好读（页面上也是 6.4s 这种写法）。 */
    public static String seconds(Double millis) {
        return millis == null ? "-" : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    public static String tps(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }

    public static String time(OffsetDateTime value) {
        return value == null ? "-" : value.toString().replace('T', ' ').substring(0, 16);
    }

    /** 把秒数说成人话：秒 / 分钟 / 小时 / 天。 */
    public static String humanDuration(long seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0L, seconds));
        if (duration.toMinutes() < 1) {
            return seconds + " 秒";
        }
        if (duration.toHours() < 1) {
            return duration.toMinutes() + " 分钟";
        }
        if (duration.toDays() < 1) {
            return duration.toHours() + " 小时";
        }
        return duration.toDays() + " 天";
    }
}