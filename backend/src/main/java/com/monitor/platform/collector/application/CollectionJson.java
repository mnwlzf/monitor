package com.monitor.platform.collector.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.common.util.SecretMasker;
import org.springframework.stereotype.Component;

/**
 * 采集数据的 JSON 序列化。
 *
 * <p>写库前的序列化统一走这里：{@link #toJson} 用于原始响应与快照，
 * {@link #toRedactedJson} 用于可能含密钥明文的变更前后值（落库前先脱敏）。</p>
 */
@Component
public class CollectionJson {

    private final ObjectMapper objectMapper;

    public CollectionJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 序列化为 JSON 文本。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化采集数据失败", ex);
        }
    }

    /**
     * 序列化变更事件前后值，并对密钥字段脱敏。
     *
     * <p>上游密钥列表返回的 {@code key} 是明文，直接写入变更事件会让明文密钥长期留在
     * 数据库里（页面详情与提醒邮件都会读到），因此统一脱敏后再落库。</p>
     */
    public String toRedactedJson(Object value) {
        return SecretMasker.redactSecretField(toJson(value), objectMapper);
    }
}
