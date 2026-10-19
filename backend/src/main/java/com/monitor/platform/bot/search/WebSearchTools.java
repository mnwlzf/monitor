package com.monitor.platform.bot.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联网搜索 / 网页抓取（DeepSeek 官方 {@code /v1/tools/search} 与 {@code /v1/tools/fetch}）。
 *
 * <p><strong>什么时候联网由模型自己判断</strong>：这两个方法以 Spring AI 工具的形式挂给
 * 管理员人设，模型根据用户问题和工具描述决定是否调用。为了不让它乱用，工具描述里写死了
 * 「只查外部实时信息；问本系统 / 机器人自身功能、命令、权限、账号数据时不要用」，
 * 系统提示词里也再强调了一遍。</p>
 *
 * <p>所有失败都翻译成可读文本返回给模型（不带堆栈、不带 API Key），绝不抛异常 ——
 * 联网失败不该把整轮回答拖垮。</p>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final String SEARCH_PATH = "/v1/tools/search";
    private static final String FETCH_PATH = "/v1/tools/fetch";
    private static final int SUMMARY_LIMIT = 900;
    private static final int CONTENT_LIMIT = 2000;

    private final WebSearchProperties properties;
    private final RestClient restClient;

    /**
     * Spring 注入用的构造器。
     *
     * <p>类里还有一个给测试注入 {@code RestClient} 的构造器，属于「多个构造器」——
     * 必须靠 {@link Autowired} 指定 Spring 用哪个，否则启动报 No default constructor found。</p>
     */
    @Autowired
    public WebSearchTools(WebSearchProperties properties) {
        this(properties, buildRestClient(properties));
    }

    /** 供测试注入 MockRestServiceServer 绑定过的 RestClient。 */
    WebSearchTools(WebSearchProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(WebSearchProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getTimeout());
        return RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(factory)
                .defaultHeader("User-Agent", "monitor-bot/1.0")
                .build();
    }

    @Tool(description = """
            联网搜索外部实时信息（新闻、产品/模型版本发布、价格、官方文档、事件等）。
            只在需要「当前/最新」的外部信息、且项目自带的工具查不到时使用。
            问本系统/机器人的功能、命令、权限、账号、余额、号池、变更记录时，
            绝对不要用这个工具，直接按系统提示词或平台工具回答。
            query 传搜索词，支持 site: / -site: / "精确短语" / lang:zh,en / after:2025 / before:2026 语法。
            count 为返回条数（1-10，默认 5）；needContent=true 会额外返回网页正文，
            一般不需要，只有摘要不够时才开。引用结果时附上链接，不要编造来源。
            """)
    public String webSearch(String query,
                            @ToolParam(required = false, description = "返回条数 1-10，默认 5") Integer count,
                            @ToolParam(required = false, description = "是否返回网页正文，默认 false") Boolean needContent) {
        return search(query, count, needContent);
    }

    @Tool(description = """
            抓取一个已知 URL 的网页正文（Markdown）。
            只在已经拿到具体链接、且搜索摘要不够时使用；不要用它猜 URL，也不要用它做搜索。
            """)
    public String webFetch(String url) {
        return fetch(url);
    }

    /** 搜索并格式化成给模型阅读的纯文本；任何失败都返回可读提示。 */
    public String search(String query, Integer count, Boolean needContent) {
        if (!properties.isConfigured()) {
            return NOT_CONFIGURED;
        }
        if (query == null || query.isBlank()) {
            return "请提供搜索词。";
        }
        String keyword = query.trim();
        int limit = clamp(count == null ? properties.getDefaultCount() : count,
                1, Math.max(1, Math.min(properties.getMaxCount(), 50)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", keyword);
        body.put("count", limit);
        body.put("need_content", Boolean.TRUE.equals(needContent));
        try {
            JsonNode data = post(SEARCH_PATH, body);
            return formatResults(keyword, data == null ? null : data.path("results"));
        } catch (RestClientResponseException ex) {
            return httpError("联网搜索", ex);
        } catch (RuntimeException ex) {
            log.warn("联网搜索失败: query={}, error={}", keyword, ex.getMessage());
            return "联网搜索暂时不可用（" + ex.getMessage() + "），请稍后再试。";
        }
    }

    /** 抓取网页 Markdown 正文；任何失败都返回可读提示。 */
    public String fetch(String url) {
        if (!properties.isConfigured()) {
            return NOT_CONFIGURED;
        }
        String target = url == null ? "" : url.trim();
        if (!isHttpUrl(target)) {
            return "请提供 http/https 开头、带域名的网页地址。";
        }
        try {
            JsonNode data = post(FETCH_PATH, Map.of("url", target));
            String title = text(data, "title", "");
            String content = text(data, "content", "");
            if (content.isBlank()) {
                return "没抓到「" + target + "」的正文（可能被站点策略拦截，或页面没有可提取的正文）。";
            }
            return "标题：" + (title.isBlank() ? "(无标题)" : title) + "\n"
                    + "URL：" + target + "\n\n"
                    + clip(content, properties.getFetchMaxChars());
        } catch (RestClientResponseException ex) {
            return httpError("网页抓取", ex);
        } catch (RuntimeException ex) {
            log.warn("网页抓取失败: url={}, error={}", target, ex.getMessage());
            return "网页抓取暂时不可用（" + ex.getMessage() + "），请稍后再试。";
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey().trim())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private String formatResults(String query, JsonNode results) {
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "没搜到「" + query + "」的相关结果。";
        }
        StringBuilder sb = new StringBuilder("搜索「").append(query).append("」共 ")
                .append(results.size()).append(" 条：");
        int index = 1;
        for (JsonNode item : results) {
            sb.append("\n\n").append(index++).append(". ").append(text(item, "title", "(无标题)"));
            String url = text(item, "url", "");
            if (!url.isBlank()) {
                sb.append("\n   ").append(url);
            }
            String site = text(item, "site_name", "");
            String date = shortDate(text(item, "date", ""));
            if (!site.isBlank() || !date.isBlank()) {
                sb.append("\n   ");
                if (!site.isBlank()) {
                    sb.append(site);
                }
                if (!date.isBlank()) {
                    sb.append(site.isBlank() ? "" : " · ").append(date);
                }
            }
            String summary = text(item, "summary", "");
            if (!summary.isBlank()) {
                sb.append("\n   摘要：").append(clip(summary, SUMMARY_LIMIT));
            }
            String content = text(item, "content", "");
            if (!content.isBlank()) {
                sb.append("\n   正文：").append(clip(content, CONTENT_LIMIT));
            }
            if (sb.length() >= properties.getSearchMaxChars()) {
                sb.append("\n\n…（结果较多，已截断）");
                break;
            }
        }
        return sb.toString();
    }

    private String httpError(String action, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        log.warn("{}失败: status={}, body={}", action, status, preview(ex.getResponseBodyAsString()));
        return switch (status) {
            case 400 -> action + "请求不合法（400）。";
            case 401 -> action + "鉴权失败（401）：请检查 MONITOR_WEB_SEARCH_API_KEY。";
            case 402 -> action + "账户余额不足（402），请先充值。";
            case 403 -> action + "被安全策略拒绝（403）。";
            case 404 -> "没抓到可用的网页内容（404）。";
            case 408 -> action + "读取超时（408），请稍后再试。";
            case 429 -> action + "被限流（429），请稍后再试。";
            case 500, 502, 503, 504 -> action + "服务暂时不可用（" + status + "），请稍后再试。";
            default -> action + "失败（HTTP " + status + "）。";
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    /** 2026-06-01T00:00:00+00:00 → 2026-06-01。 */
    private static String shortDate(String value) {
        return value != null && value.length() >= 10 ? value.substring(0, 10) : "";
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\n{3,}", "\n\n").trim();
        if (max <= 0 || cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, max) + "…";
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replace('\n', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com";
        }
        return url.replaceAll("/+$", "");
    }

    private static final String NOT_CONFIGURED =
            "联网搜索未启用：请在 .env 配置 MONITOR_WEB_SEARCH_API_KEY（api.deepseek.com 的官方 Key），"
                    + "并把 MONITOR_WEB_SEARCH_ENABLED 设为 true。";
}
