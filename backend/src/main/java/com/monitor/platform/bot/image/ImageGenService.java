package com.monitor.platform.bot.image;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用 OpenAI 兼容的生图接口（{@code POST {base-url}/v1/images/generations}）。
 *
 * <p>已实测的中转返回结构：{@code data[0].b64_json}（base64 图片）+ {@code revised_prompt}，
 * 以及 {@code usage}。所以这里直接解码 base64，不去下载外链。</p>
 *
 * <p>三个刻意的取舍：</p>
 * <ul>
 *     <li><strong>全局滑动窗口限频</strong>：一分钟合计最多 {@code max-per-minute} 张，
 *         不分人、不分群 —— 生图一次 20 秒以上且要花钱，必须有个总闸；</li>
 *     <li><strong>不抛异常</strong>：所有失败都翻译成一句给人读的话，
 *         聊天场景里抛栈没意义；</li>
 *     <li><strong>超时给足</strong>：实测 1024x1024 约 24 秒，默认 150 秒。</li>
 * </ul>
 */
@Component
public class ImageGenService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenService.class);

    private static final String PATH = "/v1/images/generations";

    private final ImageGenProperties properties;
    private final RestClient restClient;
    private final ImageRateLimiter limiter;

    @Autowired
    public ImageGenService(ImageGenProperties properties) {
        this(properties,
                buildRestClient(properties),
                new ImageRateLimiter(properties.getMaxPerMinute(), properties.getRateWindow()));
    }

    /** 供测试注入。 */
    ImageGenService(ImageGenProperties properties, RestClient restClient, ImageRateLimiter limiter) {
        this.properties = properties;
        this.restClient = restClient;
        this.limiter = limiter;
    }

    private static RestClient buildRestClient(ImageGenProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getTimeout());
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().replaceAll("/+$", "");
        return RestClient.builder()
                .baseUrl(base)
                .requestFactory(factory)
                .defaultHeader("User-Agent", "monitor-bot/1.0")
                .build();
    }

    /** 是否可用：开关打开且配了密钥与模型。 */
    public boolean isUsable() {
        return properties.isUsable();
    }

    /**
     * 生成一张图。
     *
     * @return 成功时带图片字节与上游改写后的提示词；失败时带一句可直接回复的话
     */
    public Result generate(String prompt) {
        return generate(prompt, null);
    }

    /**
     * 生成一张图，并在<strong>通过限频、真的要开始画</strong>时回调一下。
     *
     * <p>回调刻意放在限频检查之后：调用方用它发「正在画，稍等」这类开场提示，
     * 如果放在检查之前，撞上全局限频时就会先来一句「正在画…」紧接着「太频繁了」，
     * 反而更让人困惑。</p>
     *
     * @param onAccepted 真的开始画时的回调，可为 null；回调抛异常不影响生图
     */
    public Result generate(String prompt, Runnable onAccepted) {
        if (!properties.isUsable()) {
            return Result.failure("生图没开启或没配密钥。");
        }
        String text = prompt == null ? "" : prompt.trim();
        if (text.isEmpty()) {
            return Result.failure("要画什么？给我一句描述。");
        }
        int max = properties.getMaxPromptLength();
        if (text.length() > max) {
            return Result.failure("描述太长了，压缩到 " + max + " 字以内再试。");
        }
        if (!limiter.tryAcquire()) {
            long seconds = Math.max(1, limiter.retryAfter().toSeconds() + 1);
            return Result.failure("生图太频繁了，一分钟最多 " + properties.getMaxPerMinute()
                    + " 张，约 " + seconds + " 秒后再试。");
        }

        if (onAccepted != null) {
            try {
                onAccepted.run();
            } catch (RuntimeException callbackError) {
                log.warn("生图开始回调失败（不影响生图）: {}", callbackError.getMessage());
            }
        }

        try {
            JsonNode response = restClient.post()
                    .uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(text))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode first = response == null ? null : response.path("data").path(0);
            String base64 = first == null ? null : first.path("b64_json").asText(null);
            if (base64 == null || base64.isBlank()) {
                log.warn("生图返回里没有 b64_json: model={}, 响应片段={}", properties.getModel(), preview(response));
                return Result.failure("上游没有返回图片内容，稍后再试。");
            }
            byte[] image = Base64.getDecoder().decode(base64);
            String revised = first.path("revised_prompt").asText(null);
            log.info("生图成功: model={}, 提示词长度={}, 图片字节={}", properties.getModel(), text.length(), image.length);
            return Result.success(image, revised);
        } catch (RestClientResponseException ex) {
            log.warn("生图失败: status={}, body={}", ex.getStatusCode(), preview(ex.getResponseBodyAsString()));
            return Result.failure("生图服务返回错误（HTTP " + ex.getStatusCode().value() + "），稍后再试。");
        } catch (IllegalArgumentException ex) {
            log.warn("生图返回的 base64 解不开: {}", ex.getMessage());
            return Result.failure("上游返回的图片数据不完整，稍后再试。");
        } catch (RuntimeException ex) {
            log.warn("生图失败: {}", ex.getMessage());
            return Result.failure("生图失败了（" + ex.getClass().getSimpleName() + "），稍后再试。");
        }
    }

    private Map<String, Object> requestBody(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", properties.getSize());
        return body;
    }

    private static String preview(Object value) {
        if (value == null) {
            return "";
        }
        String flat = String.valueOf(value).replace('\n', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    /**
     * 生图结果。
     *
     * @param image         图片字节；失败时为 null
     * @param revisedPrompt 上游改写后的提示词，可能为 null
     * @param error         失败时给人读的一句话；成功时为 null
     */
    public record Result(byte[] image, String revisedPrompt, String error) {

        static Result success(byte[] image, String revisedPrompt) {
            return new Result(image, revisedPrompt, null);
        }

        static Result failure(String error) {
            return new Result(null, null, error);
        }

        public boolean ok() {
            return image != null && image.length > 0;
        }
    }
}
