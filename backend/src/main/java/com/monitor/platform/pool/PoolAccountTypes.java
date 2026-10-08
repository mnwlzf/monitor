package com.monitor.platform.pool;

import java.util.Locale;

/**
 * 号池账号类型工具。
 *
 * <p>本项目只关心「API Key 类型」的号池账号（上游字段 {@code type=apikey}），
 * OAuth / 其他类型账号不参与监控。上游字段大小写与分隔符可能不一致，
 * 统一归一化为「去掉非字母数字并转小写」再比较，兼容 {@code apikey}、
 * {@code api-key}、{@code api_key}、{@code APIKEY} 等写法。</p>
 */
public final class PoolAccountTypes {

    /** API Key 类型账号的归一化标识。 */
    public static final String API_KEY = "apikey";

    private PoolAccountTypes() {
    }

    /**
     * 归一化账号类型：去掉非字母数字字符并转小写。
     *
     * @return 归一化结果；入参为空时返回 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isEmpty() ? null : normalized;
    }

    /** 是否为 API Key 类型账号。 */
    public static boolean isApiKey(String raw) {
        return API_KEY.equals(normalize(raw));
    }
}