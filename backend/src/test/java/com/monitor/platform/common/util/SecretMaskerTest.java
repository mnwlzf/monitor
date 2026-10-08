package com.monitor.platform.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感信息脱敏工具测试。
 */
class SecretMaskerTest {

    private static final String RAW_KEY =
            "sk-698199a2cb9089b1795491c358f0997bfa517d8be63fcbbbaf6455f782f3fa4b";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void masksLongSecretKeepingPrefixAndSuffix() {
        assertThat(SecretMasker.mask(RAW_KEY)).isEqualTo("sk-698****fa4b");
    }

    @Test
    void keepsAlreadyMaskedOrShortValues() {
        assertThat(SecretMasker.mask("sk-abc****1234")).isEqualTo("sk-abc****1234");
        assertThat(SecretMasker.mask("short")).isEqualTo("short");
        assertThat(SecretMasker.mask(null)).isNull();
    }

    @Test
    void redactsKeyFieldAndKeepsOtherFields() {
        String json = "{\"id\":52064,\"key\":\"" + RAW_KEY + "\",\"name\":\"满血\",\"status\":\"active\"}";

        String redacted = SecretMasker.redactSecretField(json, objectMapper);

        assertThat(redacted)
                .contains("\"id\":52064")
                .contains("满血")
                .contains("sk-698****fa4b")
                .doesNotContain("698199a2");
    }

    @Test
    void returnsOriginalJsonWhenNoSecretField() {
        String json = "{\"name\":\"满血\",\"status\":\"active\"}";

        assertThat(SecretMasker.redactSecretField(json, objectMapper)).isEqualTo(json);
    }

    @Test
    void returnsNullWhenJsonCannotBeParsed() {
        assertThat(SecretMasker.redactSecretField("not-a-json", objectMapper)).isNull();
    }
}