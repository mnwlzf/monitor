package com.monitor.platform.adapter.sub2api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub2API 管理端响应解析契约。
 *
 * <p>号池客户端与平台用户客户端共用这一层，这里的宽容取值行为必须稳定：
 * 少字段、字符串数字、布尔 1/0、三种时间格式都要能收。</p>
 */
class Sub2AdminApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void unwrapsDataEnvelope() {
        JsonNode data = Sub2AdminApi.unwrapData(json("{\"code\":0,\"data\":{\"items\":[]}}"));
        assertThat(data.path("items").isArray()).isTrue();
    }

    @Test
    void keepsRootWhenDataMissing() {
        JsonNode root = json("{\"code\":0,\"message\":\"ok\"}");
        assertThat(Sub2AdminApi.unwrapData(root)).isSameAs(root);
    }

    @Test
    void extractsArrayFromNestedKeyOrDirectly() {
        assertThat(Sub2AdminApi.extractArray(json("{\"items\":[{\"id\":1},{\"id\":2}]}"))).hasSize(2);
        assertThat(Sub2AdminApi.extractArray(json("[1,2,3]"))).hasSize(3);
        assertThat(Sub2AdminApi.extractArray(json("{\"unrelated\":1}"))).isEmpty();
        assertThat(Sub2AdminApi.extractArray(null)).isEmpty();
    }

    @Test
    void readsScalarFieldsLeniently() {
        JsonNode node = json("{\"name\":\"x\",\"blank\":\"  \",\"count\":\"42\","
                + "\"rate\":\"0.18\",\"ok\":1,\"off\":\"false\"}");

        assertThat(Sub2AdminApi.text(node, "name")).isEqualTo("x");
        assertThat(Sub2AdminApi.text(node, "blank")).isNull();
        assertThat(Sub2AdminApi.text(node, "missing")).isNull();

        assertThat(Sub2AdminApi.longOrNull(node, "count")).isEqualTo(42L);
        assertThat(Sub2AdminApi.longOrNull(node, "missing")).isNull();
        assertThat(Sub2AdminApi.longValue(node, "count")).isEqualTo(42L);
        assertThat(Sub2AdminApi.longValue(node, "missing")).isZero();
        assertThat(Sub2AdminApi.intOrNull(node, "count")).isEqualTo(42);
        assertThat(Sub2AdminApi.intOr(node, "missing", 7)).isEqualTo(7);

        assertThat(Sub2AdminApi.doubleOrNull(node, "rate")).isEqualTo(0.18);
        assertThat(Sub2AdminApi.doubleOrNull(node, "name")).isNull();
        assertThat(Sub2AdminApi.doubleValue(node, "missing")).isZero();

        assertThat(Sub2AdminApi.bool(node, "ok")).isTrue();
        assertThat(Sub2AdminApi.bool(node, "off")).isFalse();
        assertThat(Sub2AdminApi.bool(node, "name")).isNull();

        assertThat(Sub2AdminApi.decimal(node, "rate")).isEqualByComparingTo(new BigDecimal("0.18"));
    }

    @Test
    void parsesTimeFormats() {
        assertThat(Sub2AdminApi.time(json("{\"t\":\"2026-10-08T10:25:44+08:00\"}"), "t"))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T10:25:44+08:00"));
        // 无偏移按业务时区（东八区）解释
        assertThat(Sub2AdminApi.time(json("{\"t\":\"2026-10-08 10:25:44\"}"), "t"))
                .isEqualTo(OffsetDateTime.parse("2026-10-08T10:25:44+08:00"));
        assertThat(Sub2AdminApi.time(json("{\"t\":\"not-a-date\"}"), "t")).isNull();
        assertThat(Sub2AdminApi.time(json("{\"t\":null}"), "t")).isNull();
    }

    @Test
    void rootMessagePrefersDeepestMessage() {
        Exception ex = new IllegalStateException("outer", new RuntimeException("inner"));
        assertThat(Sub2AdminApi.rootMessage(ex)).isEqualTo("inner");
    }

    @Test
    void firstNonBlankFallsBack() {
        assertThat(Sub2AdminApi.firstNonBlank("a", "b")).isEqualTo("a");
        assertThat(Sub2AdminApi.firstNonBlank("  ", "b")).isEqualTo("b");
        assertThat(Sub2AdminApi.firstNonBlank(null, "b")).isEqualTo("b");
    }
}
