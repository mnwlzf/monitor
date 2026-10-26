package com.monitor.platform.bot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SiliconFlow 语音能力：语音转文字 + MOSS-TTSD 文字转语音。
 *
 * <p>语音文件优先使用 OneBot 消息段里的 URL / base64；拿不到时再调用 OneBot 的
 * {@code /get_record} 转成 mp3。识别失败会自动回退到 SenseVoiceSmall，避免新模型
 * 尚未在全部账号开放时整条语音链路不可用。</p>
 */
@Service
public class BotSpeechService {

    private static final Logger log = LoggerFactory.getLogger(BotSpeechService.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final BotSpeechProperties properties;
    private final OneBotClient oneBotClient;
    private final RestClient restClient;
    private final HttpClient httpClient;

    public BotSpeechService(BotSpeechProperties properties, OneBotClient oneBotClient) {
        this.properties = properties;
        this.oneBotClient = oneBotClient;
        this.restClient = buildRestClient(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean transcriptionAvailable() {
        return properties.isTranscriptionUsable();
    }

    public boolean synthesisAvailable() {
        return properties.isTtsUsable();
    }

    public int maxTtsChars() {
        return Math.max(50, properties.getMaxTtsChars());
    }

    /** 从 OneBot 消息段中取第一条语音；没有语音时返回不可用引用。 */
    public static VoiceInput extract(JsonNode message) {
        return extract(message, null);
    }

    /** 兼容 post_format=string 的 CQ 码上报。 */
    public static VoiceInput extract(JsonNode message, String rawMessage) {
        if (message != null && message.isArray()) {
            for (JsonNode segment : message) {
                if (!"record".equals(segment.path("type").asText())) {
                    continue;
                }
                JsonNode data = segment.path("data");
                return new VoiceInput(
                        text(data, "url"),
                        text(data, "file"),
                        text(data, "file_id"),
                        text(data, "path"),
                        text(data, "base64"));
            }
        }
        return extractCqRecord(rawMessage);
    }

    private static VoiceInput extractCqRecord(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return VoiceInput.EMPTY;
        }
        Matcher matcher = Pattern.compile("\\[CQ:record,([^]]+)]").matcher(rawMessage);
        if (!matcher.find()) {
            return VoiceInput.EMPTY;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : matcher.group(1).split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = unescapeCq(part.substring(eq + 1).trim());
            values.put(key, value);
        }
        return new VoiceInput(values.getOrDefault("url", ""), values.getOrDefault("file", ""),
                values.getOrDefault("file_id", ""), values.getOrDefault("path", ""),
                values.getOrDefault("base64", ""));
    }

    private static String unescapeCq(String value) {
        return value.replace("&#44;", ",").replace("&#91;", "[").replace("&#93;", "]")
                .replace("&amp;", "&");
    }

    /**
     * 转写一条语音。
     *
     * <p>先尝试消息里直接给出的文件，再尝试 OneBot 的 get_record；主模型失败后
     * 用 SenseVoiceSmall 再试一遍。</p>
     */
    public Optional<String> transcribe(VoiceInput input) {
        if (!transcriptionAvailable() || input == null || !input.present()) {
            return Optional.empty();
        }
        List<AudioCandidate> candidates = candidates(input);
        if (candidates.isEmpty()) {
            log.warn("语音消息没有可读取的音频文件: file={}, url={}", input.file(), input.url());
            return Optional.empty();
        }
        Optional<String> text = transcribeWithModel(candidates, properties.getTranscribeModel());
        if (text.isEmpty() && !blank(properties.getFallbackTranscribeModel())
                && !properties.getFallbackTranscribeModel().equals(properties.getTranscribeModel())) {
            log.info("主语音识别模型失败，回退到 {}", properties.getFallbackTranscribeModel());
            text = transcribeWithModel(candidates, properties.getFallbackTranscribeModel());
        }
        return text;
    }

    /**
     * 合成语音。MOSS-TTSD 的 voice 形如 {@code fnlp/MOSS-TTSD-v0.5:alex}。
     * 返回 mp3 字节，由 OneBot 以 record 消息段发送。
     */
    public Optional<byte[]> synthesize(String text, String requestedVoice) {
        if (!synthesisAvailable()) {
            return Optional.empty();
        }
        String input = sanitizeForSpeech(text, properties.getMaxTtsChars());
        if (input.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getTtsModel());
        body.put("input", input);
        body.put("voice", resolveVoice(requestedVoice));
        body.put("response_format", blank(properties.getResponseFormat()) ? "mp3" : properties.getResponseFormat());
        body.put("speed", 1.0);
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri("/v1/audio/speech")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::authorization)
                    .body(body)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] audio = response.getBody();
            String responseType = response.getHeaders().getContentType() == null
                    ? "" : response.getHeaders().getContentType().toString();
            if (responseType.contains("json")) {
                log.warn("语音合成接口返回了 JSON 而不是音频: voice={}", body.get("voice"));
                return Optional.empty();
            }
            if (audio == null || audio.length == 0) {
                log.warn("语音合成返回空内容: voice={}", body.get("voice"));
                return Optional.empty();
            }
            return Optional.of(audio);
        } catch (Exception ex) {
            log.warn("语音合成失败: voice={}, reason={}", body.get("voice"), ex.getMessage());
            return Optional.empty();
        }
    }

    /** 把 MOSS 的短音色名补成完整 voice ID。 */
    public String resolveVoice(String requestedVoice) {
        String value = blank(requestedVoice) ? properties.getFallbackVoice() : requestedVoice.trim();
        if (blank(value)) {
            value = "alex";
        }
        if (value.contains(":")) {
            return value;
        }
        return properties.getTtsModel() + ":" + value;
    }

    /** 清理 Markdown 表格等不适合朗读的标记，并限制朗读长度。 */
    public static String sanitizeForSpeech(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawLine : text.replace("\r", "").split("\n")) {
            String line = rawLine.trim();
            if (line.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?")) {
                continue;
            }
            line = line.replace("**", "")
                    .replace("`", "")
                    .replaceAll("^#+\\s*", "")
                    .replace("|", "，")
                    .replaceAll("^[-*]\\s+", "");
            if (!line.isBlank()) {
                sb.append(line).append('。');
            }
        }
        String result = sb.toString()
                .replaceAll("\\s*[，、；：]\\s*", "，")
                .replaceAll("[，]{2,}", "，")
                .replaceAll("[。]{2,}", "。")
                .replaceAll("^[，。\\s]+|[，。\\s]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        int limit = Math.max(50, maxChars);
        if (result.length() > limit) {
            result = result.substring(0, limit).trim();
            int end = Math.max(result.lastIndexOf('。'), result.lastIndexOf('！'));
            if (end > limit / 2) {
                result = result.substring(0, end + 1);
            } else {
                result = result + "。";
            }
        }
        return result;
    }

    private Optional<String> transcribeWithModel(List<AudioCandidate> candidates, String model) {
        for (AudioCandidate candidate : candidates) {
            try {
                MultipartBodyBuilder builder = new MultipartBodyBuilder();
                builder.part("file", new ByteArrayResource(candidate.bytes()) {
                    @Override
                    public String getFilename() {
                        return candidate.filename();
                    }
                }).contentType(MediaType.parseMediaType(candidate.contentType()));
                builder.part("model", model);

                JsonNode response = restClient.post()
                        .uri("/v1/audio/transcriptions")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .headers(this::authorization)
                        .body(builder.build())
                        .retrieve()
                        .body(JsonNode.class);
                String text = response == null ? "" : response.path("text").asText("").trim();
                if (!text.isBlank()) {
                    log.info("语音识别成功: model={}, chars={}", model, text.length());
                    return Optional.of(text);
                }
            } catch (Exception ex) {
                log.warn("语音识别失败: model={}, file={}, reason={}",
                        model, candidate.filename(), ex.getMessage());
            }
        }
        return Optional.empty();
    }

    private List<AudioCandidate> candidates(VoiceInput input) {
        List<AudioCandidate> result = new ArrayList<>();
        addBase64(result, input.base64(), input.file(), "voice.mp3");
        addUrl(result, input.url());
        addUrl(result, input.file());
        addUrl(result, input.path());
        addLocal(result, input.file());
        addLocal(result, input.path());

        boolean needsConversion = result.isEmpty() || result.stream()
                .anyMatch(candidate -> candidate.filename().endsWith(".amr")
                        || candidate.filename().endsWith(".silk"));
        if (needsConversion && (!blank(input.file()) || !blank(input.fileId()))) {
            try {
                JsonNode record = oneBotClient.getRecord(input.file(), input.fileId(), properties.getRecordFormat());
                if (record != null && !record.isMissingNode() && !record.isNull()) {
                    addBase64(result, text(record, "base64"), text(record, "file"), "voice." + properties.getRecordFormat());
                    addUrl(result, text(record, "url"));
                    addUrl(result, text(record, "file"));
                    addLocal(result, text(record, "file"));
                }
            } catch (Exception ex) {
                log.debug("OneBot get_record 失败: file={}, reason={}", input.file(), ex.getMessage());
            }
        }
        return result;
    }

    private void addUrl(List<AudioCandidate> result, String value) {
        if (blank(value) || !(value.startsWith("http://") || value.startsWith("https://"))) {
            return;
        }
        AudioCandidate candidate = download(value);
        addUnique(result, candidate);
    }

    private void addLocal(List<AudioCandidate> result, String value) {
        if (blank(value) || value.startsWith("http://") || value.startsWith("https://") || value.startsWith("base64://")) {
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".amr")
                || lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".flac")
                || lower.endsWith(".silk"))) {
            return;
        }
        try {
            Path path = Path.of(value);
            if (!Files.isRegularFile(path) || Files.size(path) > properties.getMaxAudioBytes()) {
                return;
            }
            addUnique(result, new AudioCandidate(Files.readAllBytes(path), path.getFileName().toString(),
                    contentType(path.getFileName().toString())));
        } catch (Exception ex) {
            log.debug("读取本地语音失败: path={}, reason={}", value, ex.getMessage());
        }
    }

    private void addBase64(List<AudioCandidate> result, String value, String filenameHint, String fallbackName) {
        if (blank(value)) {
            return;
        }
        try {
            String encoded = value.trim();
            if (encoded.startsWith("data:")) {
                int comma = encoded.indexOf(',');
                if (comma >= 0) {
                    encoded = encoded.substring(comma + 1);
                }
            }
            if (encoded.startsWith("base64://")) {
                encoded = encoded.substring("base64://".length());
            }
            byte[] bytes = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
            if (bytes.length == 0 || bytes.length > properties.getMaxAudioBytes()) {
                return;
            }
            String filename = audioFilename(filenameHint, fallbackName);
            addUnique(result, new AudioCandidate(bytes, filename, contentType(filename)));
        } catch (Exception ex) {
            log.debug("解析语音 base64 失败: {}", ex.getMessage());
        }
    }

    private AudioCandidate download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.getTimeout())
                    .header("User-Agent", "monitor-bot/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("下载语音失败: status={}, url={}", response.statusCode(), url);
                closeQuietly(response.body());
                return null;
            }
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > properties.getMaxAudioBytes()) {
                closeQuietly(response.body());
                return null;
            }
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes((int) Math.min(properties.getMaxAudioBytes() + 1, Integer.MAX_VALUE - 8));
            }
            if (bytes.length == 0 || bytes.length > properties.getMaxAudioBytes()) {
                return null;
            }
            String filename = audioFilename(url, "voice.mp3");
            String contentType = response.headers().firstValue("content-type")
                    .orElse(contentType(filename));
            return new AudioCandidate(bytes, filename, normalizeAudioType(contentType));
        } catch (Exception ex) {
            log.warn("下载语音异常: url={}, reason={}", url, ex.getMessage());
            return null;
        }
    }

    private static void addUnique(List<AudioCandidate> result, AudioCandidate candidate) {
        if (candidate == null || candidate.bytes().length == 0) {
            return;
        }
        for (AudioCandidate existing : result) {
            if (Arrays.equals(existing.bytes(), candidate.bytes())) {
                return;
            }
        }
        result.add(candidate);
    }

    private static String audioFilename(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.trim();
        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }
        int slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'));
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".amr")
                || lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".flac")
                || lower.endsWith(".silk")) {
            return cleaned;
        }
        return fallback;
    }

    private static String contentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".amr")) return "audio/amr";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".silk")) return "audio/silk";
        return "audio/mpeg";
    }

    private static String normalizeAudioType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.startsWith("application/octet-stream")) {
            return "audio/mpeg";
        }
        return contentType.split(";")[0].trim();
    }

    private void authorization(HttpHeaders headers) {
        if (!blank(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }
    }

    private static RestClient buildRestClient(BotSpeechProperties properties) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        if (base.isBlank()) {
            base = "https://api.siliconflow.cn";
        }
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1000, properties.getTimeout().toMillis()));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, timeout));
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(base.replaceAll("/+$", "")).requestFactory(factory).build();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /** OneBot record 段中的原始引用。 */
    public record VoiceInput(String url, String file, String fileId, String path, String base64) {
        public static final VoiceInput EMPTY = new VoiceInput("", "", "", "", "");

        public boolean present() {
            return !blank(url) || !blank(file) || !blank(fileId) || !blank(path) || !blank(base64);
        }
    }

    private record AudioCandidate(byte[] bytes, String filename, String contentType) {
    }
}
