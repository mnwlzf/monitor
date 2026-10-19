package com.monitor.platform.bot.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepSeek 联网搜索 / 抓取：报文解析、错误翻译与未配置时的降级。
 *
 * <p>接口按成功请求计费，测试一律拦 HTTP，不打真实接口。</p>
 */
class WebSearchToolsTest {

    private static final String SEARCH_BODY = """
            {"results":[
              {"rerank_score":2.4,"title":"DeepSeek V4 发布","url":"https://example.com/v4",
               "snippet":"短摘要","summary":"长摘要：V4 支持工具调用与长上下文。",
               "content":"","site_name":"Example News","site_icon":null,
               "date":"2026-06-01T00:00:00+00:00","authority":3}
            ]}
            """;

    private WebSearchProperties properties;
    private MockRestServiceServer server;
    private WebSearchTools tools;

    @BeforeEach
    void setUp() {
        properties = new WebSearchProperties();
        properties.setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
        server = MockRestServiceServer.bindTo(builder).build();
        tools = new WebSearchTools(properties, builder.build());
    }

    @Test
    void shouldSearchAndFormatResults() {
        server.expect(requestTo(containsString("/v1/tools/search")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().string(containsString("DeepSeek V4")))
                .andExpect(content().string(containsString("\"count\":3")))
                .andRespond(withSuccess(SEARCH_BODY, MediaType.APPLICATION_JSON));

        String text = tools.webSearch("DeepSeek V4", 3, false);

        assertTrue(text.contains("DeepSeek V4 发布"), text);
        assertTrue(text.contains("https://example.com/v4"), text);
        assertTrue(text.contains("长摘要"), text);
        assertTrue(text.contains("Example News"), text);
        assertTrue(text.contains("2026-06-01"), text);
        server.verify();
    }

    @Test
    void shouldExplainEmptyResults() {
        server.expect(requestTo(containsString("/v1/tools/search")))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(tools.search("没有结果", 5, false).contains("没搜到"));
        server.verify();
    }

    @Test
    void shouldNotCallAnythingWhenNotConfigured() {
        properties.setApiKey("");

        assertTrue(tools.search("DeepSeek", 5, false).contains("未启用"));
        assertTrue(tools.fetch("https://example.com").contains("未启用"));
        server.verify();
    }

    @Test
    void shouldFetchMarkdownContent() {
        server.expect(requestTo(containsString("/v1/tools/fetch")))
                .andExpect(content().string(containsString("https://example.com/a")))
                .andRespond(withSuccess(
                        "{\"title\":\"示例文章\",\"content\":\"# 标题\\n正文\"}",
                        MediaType.APPLICATION_JSON));

        String text = tools.webFetch("https://example.com/a");

        assertTrue(text.contains("示例文章"), text);
        assertTrue(text.contains("# 标题"), text);
        assertTrue(text.contains("正文"), text);
        server.verify();
    }

    @Test
    void shouldTranslateRateLimit() {
        server.expect(requestTo(containsString("/v1/tools/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertTrue(tools.search("DeepSeek", 5, false).contains("限流"));
        server.verify();
    }

    @Test
    void shouldRejectNonHttpUrl() {
        assertTrue(tools.fetch("file:///etc/passwd").contains("http"));
        assertTrue(tools.fetch("not a url").contains("http"));
        server.verify();
    }

    @Test
    void shouldClampCountToConfiguredMax() {
        properties.setMaxCount(10);
        server.expect(requestTo(containsString("/v1/tools/search")))
                .andExpect(content().string(containsString("\"count\":10")))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        tools.search("DeepSeek", 99, false);

        server.verify();
    }
}
