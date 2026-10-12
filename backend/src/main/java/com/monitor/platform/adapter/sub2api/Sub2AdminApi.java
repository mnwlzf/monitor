package com.monitor.platform.adapter.sub2api;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sub2API 管理端只读接口的公共支撑。
 *
 * <p>号池客户端（{@code Sub2AdminClient}）与平台用户客户端（{@code Sub2UserAdminClient}）
 * 打的是同一套 {@code x-api-key} 管理端接口，响应信封、分页数组位置与字段类型都一致。
 * 这里集中处理「调用 + 解信封 + 字段取值」，避免两处解析口径各写一份后逐渐漂移。</p>
 *
 * <p>全部是纯静态工具，不持有状态。</p>
 */
public final class Sub2AdminApi {

    private static final Logger log = LoggerFactory.getLogger(Sub2AdminApi.class);

    private static final int MAX_ERROR_LENGTH = 300;

    /** 分页 / 列表结构里可能承载数组的字段名，按优先级排列。 */
    private static final String[] ARRAY_KEYS =
            {"items", "list", "records", "rows", "accounts", "results", "data"};

    private Sub2AdminApi() {
    }

    /**
     * 执行一次管理端请求：解出业务数据节点，并把网络 / 解析异常转成可读的业务异常。
     *
     * <p>管理端接口响应体较大，读取阶段超时会被底层抛出 {@code IOException: closed}，
     * 这里转成明确提示，避免页面只看到「服务器内部错误」。</p>
     */
    public static JsonNode execute(Supplier<JsonNode> call, String action) {
        try {
            return unwrapData(call.get());
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            log.warn("Sub2API 管理端接口调用失败: action={}, status={}", action, status.value());
            throw BusinessException.of(
                    action + "失败：上游返回 " + status.value() + "，请检查管理员密钥是否有效");
        } catch (RestClientException ex) {
            String reason = rootMessage(ex);
            log.warn("Sub2API 管理端接口调用失败: action={}, reason={}", action, reason);
            throw BusinessException.of(action + "失败：" + reason);
        }
    }

    /** 解析响应外层信封，返回业务数据节点。 */
    public static JsonNode unwrapData(JsonNode root) {
        if (root == null) {
            throw new IllegalStateException("Sub2API 管理端接口返回为空");
        }
        JsonNode codeNode = root.path("code");
        if (codeNode.isNumber() && codeNode.asInt() != 0) {
            throw new IllegalStateException("Sub2API 管理端接口返回错误，code=" + codeNode.asInt()
                    + "，message=" + root.path("message").asText(""));
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() ? root : data;
    }

    /** 从数据节点中提取数组，兼容直接返回数组或多种列表字段。 */
    public static List<JsonNode> extractArray(JsonNode data) {
        if (data == null || data.isNull()) {
            return List.of();
        }
        if (data.isArray()) {
            return toList(data);
        }
        for (String key : ARRAY_KEYS) {
            JsonNode candidate = data.path(key);
            if (candidate.isArray()) {
                return toList(candidate);
            }
        }
        return List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>(array.size());
        array.forEach(list::add);
        return list;
    }

    // ---------------------------------------------------------------- 字段取值

    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    public static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 取 long，缺省为 0。 */
    public static long longValue(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? 0L : value;
    }

    public static Integer intOrNull(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? null : value.intValue();
    }

    /** 取 int，缺省用 {@code fallback}。 */
    public static int intOr(JsonNode node, String field, int fallback) {
        Integer value = intOrNull(node, field);
        return value == null ? fallback : value;
    }

    public static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 取 double，缺省为 0。 */
    public static double doubleValue(JsonNode node, String field) {
        Double value = doubleOrNull(node, field);
        return value == null ? 0d : value;
    }

    /** 宽松布尔：兼容 {@code true/false} 与 {@code 1/0}，无法判定时返回 null。 */
    public static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 解析时间字段，兼容带偏移、瞬时、无偏移（按业务时区）三种格式。 */
    public static OffsetDateTime time(JsonNode node, String field) {
        String text = text(node, field);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(BusinessTime.ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            log.debug("无法解析 Sub2API 时间字段 {}={}", field, text);
            return null;
        }
    }

    /** 提取最内层异常信息并截断，便于在页面展示。 */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    /** 取第一个非空字符串，都为空时返回第二个。 */
    public static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
