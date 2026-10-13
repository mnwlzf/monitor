package com.monitor.platform.collector.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集 JSON 序列化契约。
 *
 * <p>{@link CollectionJson#toRedactedJson} 是一道安全边界：变更事件里可能带着上游返回的
 * 明文密钥，落库前必须先脱敏，否则明文会扩散到数据库、邮件和页面。</p>
 */
class CollectionJsonTest {

    private final CollectionJson json = new CollectionJson(new ObjectMapper());

    @Test
    void serializesPlainObject() {
        assertThat(json.toJson(Map.of("a", 1))).isEqualTo("{\"a\":1}");
    }

    @Test
    void redactsSecretFieldBeforePersisting() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "满血");
        payload.put("key", "sk-698199a2cb9089b1795491c358f0997bfa517d8be63fcbbbaf6455f782f3fa4b");

        String redacted = json.toRedactedJson(payload);

        assertThat(redacted)
                .contains("满血")
                .contains("sk-698****fa4b")
                .doesNotContain("698199a2cb9089b1795491c358f0997bfa517d8be63fcbbbaf6455f782");
    }
}
