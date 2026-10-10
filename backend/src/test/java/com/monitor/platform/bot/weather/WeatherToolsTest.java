package com.monitor.platform.bot.weather;

import com.monitor.platform.bot.report.BotReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 天气查询：报文解析、预报、错误处理与缓存。
 *
 * <p>用 MockRestServiceServer 拦住 HTTP，不打真实接口 —— 免费额度按次计，
 * 测试更不该去消耗它。</p>
 */
class WeatherToolsTest {

    private static final String CURRENT = """
            {"province":"北京市","city":"北京","adcode":"110000","weather":"晴","weather_icon":"100",
             "temperature":26,"wind_direction":"东风","wind_power":"1级","humidity":34,
             "report_time":"2026-10-10 16:34:09","feels_like":25,"visibility":11.3,"pressure":1013,
             "uv":4,"aqi":49,"aqi_level":1,"aqi_category":"优","aqi_primary":"O3",
             "precipitation":0,"cloud":77}
            """;

    private static final String WITH_FORECAST = """
            {"province":"北京市","city":"北京","weather":"晴","temperature":26,"feels_like":25,
             "wind_direction":"东风","wind_power":"1级","humidity":34,"report_time":"2026-10-10 16:34:09",
             "aqi":49,"aqi_category":"优","uv":4,
             "forecast":[
               {"date":"2026-10-10","week":"星期六","temp_max":26,"temp_min":14,
                "weather_day":"多云","weather_night":"晴","pop":0},
               {"date":"2026-10-11","week":"星期日","temp_max":25,"temp_min":15,
                "weather_day":"阴","weather_night":"小雨","pop":30}
             ]}
            """;

    private WeatherProperties properties;
    private MockRestServiceServer server;
    private WeatherTools tools;

    @BeforeEach
    void setUp() {
        properties = new WeatherProperties();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://uapis.cn");
        server = MockRestServiceServer.bindTo(builder).build();
        tools = new WeatherTools(properties, builder.build());
    }

    @Test
    void shouldBuildCurrentWeatherReport() {
        server.expect(requestTo(containsString("/api/v1/misc/weather")))
                .andRespond(withSuccess(CURRENT, MediaType.APPLICATION_JSON));

        BotReport report = tools.weatherReport("北京", 0);

        assertEquals("北京 · 实时天气", report.title());
        String text = report.toPlainText();
        assertTrue(text.contains("晴"), text);
        assertTrue(text.contains("26°C"), text);
        assertTrue(text.contains("体感 25°C"), text);
        assertTrue(text.contains("优"), text);
        assertTrue(text.contains("AQI 49"), text);
        server.verify();
    }

    @Test
    void shouldIncludeForecastWhenRequested() {
        server.expect(requestTo(containsString("/api/v1/misc/weather")))
                .andRespond(withSuccess(WITH_FORECAST, MediaType.APPLICATION_JSON));

        BotReport report = tools.weatherReport("北京", 2);

        String text = report.toPlainText();
        assertTrue(text.contains("未来 2 天"), text);
        assertTrue(text.contains("10-10"), text);
        // 白天夜间不同时写成「多云转晴」，温度写成 26/14°C —— 都是给人直接读的写法
        assertTrue(text.contains("多云转晴"), text);
        assertTrue(text.contains("26/14°C"), text);
        assertTrue(text.contains("降水 30%"), text);
        server.verify();
    }

    /**
     * 天气是给人直接看的，不能出现 Markdown 表格。
     *
     * <p>竖线和 {@code |---|} 分隔行在 QQ 里读起来很别扭 —— 这条断言把这个要求固定下来。</p>
     */
    @Test
    void shouldNotUseMarkdownTables() {
        server.expect(requestTo(containsString("/api/v1/misc/weather")))
                .andRespond(withSuccess(WITH_FORECAST, MediaType.APPLICATION_JSON));

        String text = tools.weatherReport("北京", 3).toPlainText();

        assertFalse(text.contains("|"), "天气不该出现 Markdown 竖线：" + text);
        assertFalse(text.contains("---"), "天气不该出现 Markdown 分隔行：" + text);
    }

    /** 城市不存在时接口返回 404（不是 JSON 错误体），要翻译成可读提示。 */
    @Test
    void shouldExplainUnknownCity() {
        server.expect(requestTo(containsString("/api/v1/misc/weather")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        BotReport report = tools.weatherReport("NotARealCityXyz", 0);

        assertTrue(report.toPlainText().contains("没找到"), report.toPlainText());
        server.verify();
    }

    @Test
    void shouldAskForCityWhenMissing() {
        assertTrue(tools.weatherReport("", 0).toPlainText().contains("哪个城市"));
        assertTrue(tools.weatherReport(null, 0).toPlainText().contains("哪个城市"));
    }

    @Test
    void shouldReportWhenDisabled() {
        properties.setEnabled(false);
        assertTrue(tools.weatherReport("北京", 0).toPlainText().contains("未启用"));
    }

    /** 同一城市短时间内重复问，只应打一次接口（免费额度按次计）。 */
    @Test
    void shouldCacheRepeatedQueries() {
        server.expect(requestTo(containsString("/api/v1/misc/weather")))
                .andRespond(withSuccess(CURRENT, MediaType.APPLICATION_JSON));

        tools.weatherReport("北京", 0);
        tools.weatherReport("北京", 0);

        // 只声明了一次期望：第二次若真的发请求，MockRestServiceServer 会报错
        server.verify();
    }

    @Test
    void shouldDefaultForecastDaysFromProperties() {
        assertEquals(3, tools.defaultForecastDays());

        properties.setDefaultForecastDays(99);
        assertEquals(7, tools.defaultForecastDays(), "超过上限要夹紧");
    }
}