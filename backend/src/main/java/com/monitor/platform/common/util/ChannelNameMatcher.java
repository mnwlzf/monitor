package com.monitor.platform.common.util;

import java.util.Locale;
import java.util.Set;

/**
 * 上游渠道（分组）名的比对。
 *
 * <p>成本影响分析允许「改名前后缀」匹配：Key 上记录的分组名可能比渠道名滞后一轮采集。
 * 但实时告警的「使用中」判定必须更严格 —— 前缀相似不代表同一个渠道，否则容易把
 * 「没人用的相似渠道」误判成正在使用，造成邮件噪声。</p>
 */
public final class ChannelNameMatcher {

    private ChannelNameMatcher() {
    }

    /** 两个渠道名是否可能指向同一个渠道（完全一致，或互为前缀）。 */
    public static boolean sameChannel(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.trim().toLowerCase(Locale.ROOT);
        String b = right.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    /** 渠道名是否命中给定的任意一个渠道名（用于成本影响分析）。 */
    public static boolean matchesAny(Set<String> channelNames, String channel) {
        if (channelNames == null || channelNames.isEmpty() || channel == null || channel.isBlank()) {
            return false;
        }
        return channelNames.stream().anyMatch(name -> sameChannel(name, channel));
    }

    /** 严格同名匹配（去空白、忽略大小写），用于实时告警的「使用中」判定。 */
    public static boolean exactSameChannel(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = left.trim();
        String b = right.trim();
        return !a.isEmpty() && !b.isEmpty() && a.equalsIgnoreCase(b);
    }
}