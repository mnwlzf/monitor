package com.monitor.platform.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 敏感信息脱敏工具。
 *
 * <p>变更事件会保存上游原始响应，而密钥列表接口返回的 {@code key} 是明文；
 * 落库与展示前都必须先脱敏，避免明文密钥扩散到数据库、邮件和页面。</p>
 */
public final class SecretMasker {

    /** 需要脱敏的字段名（密钥列表响应里的明文字段）。 */
    public static final String SECRET_FIELD = "key";

    private SecretMasker() {
    }

    /**
     * 脱敏单个密钥值：保留前 6 位与后 4 位。
     *
     * <p>已脱敏（含 {@code *}）或长度不超过 12 的值原样返回。</p>
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.contains("*") || value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 把 JSON 对象里 {@link #SECRET_FIELD} 字段脱敏后重新序列化。
     *
     * <p>无法解析时返回 {@code null}：宁可丢字段，也不把可能包含明文密钥的原始文本写进去。</p>
     *
     * @param json         原始 JSON 文本
     * @param objectMapper Jackson 实例
     * @return 脱敏后的 JSON；不需要脱敏时原样返回
     */
    public static String redactSecretField(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject() || !node.hasNonNull(SECRET_FIELD)) {
                return json;
            }
            ((ObjectNode) node).put(SECRET_FIELD, mask(node.path(SECRET_FIELD).asText(null)));
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}