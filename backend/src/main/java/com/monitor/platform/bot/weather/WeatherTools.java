package com.monitor.platform.bot.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.report.BotReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气查询（uapis.cn 免费接口，无需注册与 Key）。
 *
 * <p><strong>所有人都能用</strong>：天气与平台无关，因此三种人设（管理员 / 平台用户 / 陌生人）
 * 都会挂载这个工具，陌生人也能正常问天气 —— 它不会泄露任何平台信息。</p>
 *
 * <p>接口文档：{@code GET /api/v1/misc/weather}，参数 {@code city} / {@code lang} /
 * {@code extended} / {@code forecast}；城市不存在时返回 404（不是 JSON 错误体），
 * 所以这里单独把 404 翻译成「没找到这个城市」。</p>
 */
@Component
public class WeatherTools {

    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

    private static final String PATH = "/api/v1/misc/weather";
    private static final int MAX_FORECAST_DAYS = 7;

    private final WeatherProperties properties;
    private final RestClient restClient;

    /** 同一城市的短缓存：免费额度按次计，群里反复问同一个城市没必要每次都打接口。 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public WeatherTools(WeatherProperties properties) {
        this(properties, buildRestClient(properties));
    }

    /** 供测试注入 MockRestServiceServer 绑定过的 RestClient。 */
    WeatherTools(WeatherProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(WeatherProperties properties) {
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

    /** 默认预报天数，供命令解析使用。 */
    public int defaultForecastDays() {
        return Math.min(Math.max(properties.getDefaultForecastDays(), 0), MAX_FORECAST_DAYS);
    }

    /**
     * 查询天气。
     *
     * @param city 城市名，支持中文（北京）与英文（Tokyo）
     * @param days 预报天数 0-7，0 表示只要当前天气
     */
    @Tool(description = """
            查询指定城市的实时天气与未来几天预报。所有人都可以用这个工具。
            city 传城市名，支持中文（北京、上海）和英文（Tokyo、London）。
            days 是预报天数（0-7），不传或传 0 表示只查当前天气。
            用户没给城市时，先问他想查哪个城市，不要自己猜。
            """)
    public String weather(String city, int days) {
        return weatherReport(city, days).toPlainText();
    }

    /** 结构化天气报表：确定性命令 /天气 与图片渲染都走它。 */
    public BotReport weatherReport(String city, int days) {
        if (!properties.isEnabled()) {
            return BotReport.fromMarkdown("天气查询未启用（MONITOR_WEATHER_ENABLED=false）。");
        }
        if (city == null || city.isBlank()) {
            return BotReport.fromMarkdown("请告诉我想查哪个城市，例如：/天气 北京");
        }
        String trimmed = city.trim();
        int forecastDays = Math.min(Math.max(days, 0), MAX_FORECAST_DAYS);

        JsonNode data = fetch(trimmed, forecastDays);
        if (data == null) {
            return BotReport.fromMarkdown("天气服务暂时不可用，请稍后再试。");
        }
        if (data.isMissingNode() || data.isEmpty()) {
            return BotReport.fromMarkdown("没找到「" + trimmed + "」这个城市，换个写法试试（比如 北京 / Tokyo）。");
        }
        return buildReport(trimmed, data, forecastDays);
    }

    private JsonNode fetch(String city, int forecastDays) {
        String key = city.toLowerCase(Locale.ROOT) + "|" + forecastDays;
        Cached cached = cache.get(key);
        if (cached != null && !cached.expired(properties.getCacheTtl())) {
            return cached.data();
        }

        try {
            JsonNode data = restClient.get()
                    .uri(builder -> builder.path(PATH)
                            .queryParam("city", city)
                            .queryParam("lang", "zh")
                            // extended 提供体感温度、能见度、气压、紫外线与空气质量
                            .queryParam("extended", true)
                            .queryParam("forecast", forecastDays > 0)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (data != null && !data.isMissingNode()) {
                cache.put(key, new Cached(data, Instant.now()));
            }
            return data;
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 404) {
                // 城市不存在：返回空对象，由调用方给出「换个写法」的提示
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            }
            log.warn("天气接口返回异常: city={}, status={}", city, status.value());
            return null;
        } catch (Exception ex) {
            log.warn("查询天气失败: city={}, reason={}", city, ex.getMessage());
            return null;
        }
    }

    private BotReport buildReport(String query, JsonNode data, int forecastDays) {
        String cityName = text(data, "city", query);
        String title = cityName + " · 实时天气";

        List<BotReport.Row> current = new ArrayList<>();
        current.add(BotReport.Row.of("天气", text(data, "weather", "-")));
        current.add(BotReport.Row.of("温度", temperature(data, "temperature", "feels_like")));
        current.add(BotReport.Row.of("湿度", percentValue(data, "humidity")));
        current.add(BotReport.Row.of("风力", wind(data)));
        current.add(BotReport.Row.of("空气质量", airQuality(data)));
        current.add(BotReport.Row.of("紫外线", value(data, "uv")));
        current.add(BotReport.Row.of("能见度", withUnit(data, "visibility", " km")));
        current.add(BotReport.Row.of("气压", withUnit(data, "pressure", " hPa")));
        current.add(BotReport.Row.of("更新时间", text(data, "report_time", "-")));

        List<BotReport.Block> blocks = new ArrayList<>();
        blocks.add(new BotReport.Block(district(data), List.of(
                BotReport.Col.left("项目"),
                BotReport.Col.left("值")), current, null));

        if (forecastDays > 0 && data.path("forecast").isArray() && !data.path("forecast").isEmpty()) {
            List<BotReport.Row> rows = new ArrayList<>();
            for (JsonNode day : data.path("forecast")) {
                rows.add(BotReport.Row.of(
                        shortDate(text(day, "date", "-")),
                        text(day, "week", "-"),
                        text(day, "weather_day", "-") + " / " + text(day, "weather_night", "-"),
                        range(day),
                        percentValue(day, "pop")));
            }
            blocks.add(new BotReport.Block("未来 " + rows.size() + " 天预报", List.of(
                    BotReport.Col.left("日期"),
                    BotReport.Col.left("星期"),
                    BotReport.Col.left("白天 / 夜间"),
                    BotReport.Col.left("最高 / 最低"),
                    BotReport.Col.right("降水概率")), rows, null));
        }

        List<String> notes = new ArrayList<>();
        JsonNode alerts = data.path("alerts");
        if (alerts.isArray()) {
            for (JsonNode alert : alerts) {
                notes.add("⚠ " + text(alert, "title", "气象预警") + "：" + text(alert, "type", "")
                        + text(alert, "level", ""));
            }
        }
        return BotReport.of(title, blocks, notes);
    }

    // ---------- 字段取值 ----------

    /**
     * 块标题：只在有「区县」时返回（按 IP 定位时常见）。
     *
     * <p>不返回省+市 —— 标题里已经有城市名了，再写一遍「北京市 北京」是冗余。</p>
     */
    private static String district(JsonNode data) {
        String district = text(data, "district", "");
        return district.isBlank() ? null : district;
    }

    private static String temperature(JsonNode data, String field, String feelsLikeField) {
        String base = withUnit(data, field, "°C");
        JsonNode feels = data.path(feelsLikeField);
        if (feels.isNumber()) {
            return base + "（体感 " + trim(feels.asText()) + "°C）";
        }
        return base;
    }

    private static String wind(JsonNode data) {
        String direction = text(data, "wind_direction", "");
        String power = text(data, "wind_power", "");
        String value = (direction + " " + power).trim();
        return value.isBlank() ? "-" : value;
    }

    private static String airQuality(JsonNode data) {
        JsonNode aqi = data.path("aqi");
        if (!aqi.isNumber()) {
            return "-";
        }
        String category = text(data, "aqi_category", "");
        String primary = text(data, "aqi_primary", "");
        StringBuilder sb = new StringBuilder();
        if (!category.isBlank()) {
            sb.append(category).append(" ");
        }
        sb.append("(AQI ").append(aqi.asInt()).append(")");
        if (!primary.isBlank()) {
            sb.append(" 首要污染物 ").append(primary);
        }
        return sb.toString();
    }

    private static String range(JsonNode day) {
        String max = value(day, "temp_max");
        String min = value(day, "temp_min");
        if ("-".equals(max) && "-".equals(min)) {
            return "-";
        }
        return max + "°C / " + min + "°C";
    }

    private static String percentValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() + "%" : "-";
    }

    private static String withUnit(JsonNode node, String field, String unit) {
        String value = value(node, field);
        return "-".equals(value) ? "-" : value + unit;
    }

    private static String value(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "-";
        }
        if (value.isNumber()) {
            return trim(value.asText());
        }
        String text = value.asText();
        return text == null || text.isBlank() ? "-" : text;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    /** 26.0 → 26；26.35 → 26.4。 */
    private static String trim(String number) {
        try {
            double parsed = Double.parseDouble(number);
            if (parsed == Math.floor(parsed)) {
                return String.valueOf((long) parsed);
            }
            return String.format(Locale.ROOT, "%.1f", parsed);
        } catch (NumberFormatException ex) {
            return number;
        }
    }

    /** 2026-10-10 → 10-10。 */
    private static String shortDate(String date) {
        try {
            LocalDate parsed = LocalDate.parse(date);
            return String.format(Locale.ROOT, "%02d-%02d", parsed.getMonthValue(), parsed.getDayOfMonth());
        } catch (DateTimeParseException ex) {
            return date;
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://uapis.cn";
        }
        return url.replaceAll("/+$", "");
    }

    private record Cached(JsonNode data, Instant fetchedAt) {

        boolean expired(Duration ttl) {
            return ttl == null || ttl.isZero() || ttl.isNegative()
                    || fetchedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}