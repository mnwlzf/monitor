package com.monitor.platform.common.util;

import java.util.Locale;
import java.util.Set;

/**
 * 上游渠道（分组）名的比对。
 *
 * <p>上游改分组名是常态，而且 Key 上记着的分组名往往滞后一轮采集 —— 例如渠道已经叫
 * 「官Key-不降智-无6.1sol」，Key 上还写着「官Key-不降智」。严格相等会让「我正在用的渠道」
 * 和「我绑定的 Key」对不上，所以这里统一口径：<strong>完全一致，或两个名字互为前缀
 * （改名前后）</strong>就算同一个渠道。</p>
 *
 * <p>「使用中」判定（{@code UpstreamChangeRecorder}）与成本影响评估
 * （{@code RateChangeImpactService}）都走这里，避免两处口径不一致。</p>
 */
public final class ChannelNameMatcher {

    private ChannelNameMatcher() {
    }

    /** 两个渠道名是否指向同一个渠道。 */
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

    /** 渠道名是否命中给定的任意一个渠道名。 */
    public static boolean matchesAny(Set<String> channelNames, String channel) {
        if (channelNames == null || channelNames.isEmpty() || channel == null || channel.isBlank()) {
            return false;
        }
        return channelNames.stream().anyMatch(name -> sameChannel(name, channel));
    }
}
