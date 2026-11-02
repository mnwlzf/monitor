package com.monitor.platform.bot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机器人语音能力：阿里云百炼 Qwen-Audio-ASR 语音转文字 + Qwen-Audio-TTS 语音合成。
 *
 * <p>语音文件优先使用 OneBot 消息段里的 URL / base64；拿不到时再调用 OneBot 的
 * {@code /get_record} 转成 mp3。ASR 使用同步 HTTP API 和 Base64 Data URI，
 * TTS 使用 WebSocket 实时协议，音频通过 binary 帧返回后拼成 mp3。</p>
 */
@Service
public class BotSpeechService {

    private static final Logger log = LoggerFactory.getLogger(BotSpeechService.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final String DEFAULT_VOICE = "longanhuan_v3.1";
    private static final Set<String> QWEN_VOICES = Set.of(
            "longanhuan_v3.1", "longanlingxin_v3.1", "longanfengyue_v3.1", "xunanchuan_v3.1",
            "yuxiaoyun_v3.1", "qiaoxiaojiao_v3.1", "xiaxiaochen_v3.1", "anmingyuan_v3.1",
            "wenhuaiqing_v3.1", "anxiaolan_v3.1", "xieshurou_v3.1", "baiqinglan_v3.1",
            "xuyuyuan_v3.1", "anruorou_v3.1", "wenhuaizhi_v3.1", "xiaoxingzhi_v3.1",
            "guyunshu_v3.1", "huozhuoshi_v3.1", "yeqinghe_v3.1", "yunhuanhuan_v3.1",
            "xuxiaoqiao_v3.1", "baianran_v3.1", "xuyanchu_v3.1", "yezhiqing_v3.1",
            "andi_v3.1", "anyuqing_v3.1", "linxiaoling_v3.1", "longfeifei_v3.1",
            "longjing_v3.1", "longqiang_v3.1", "longyan_v3.1", "longxiaobai_v3.1",
            "longyue_v3.1", "longcheng_v3.1", "longlaotie_v3.1", "longshao_v3.1",
            "longshu_v3.1", "longze_v3.1", "longhao_v3.1", "longjin_v3.1",
            "longnan_v3.1", "longshuo_v3.1", "longtian_v3.1", "longxiaocheng_v3.1",
            "longke_v3.1", "loongbella_v3.1", "Emily_v3.1", "Luna_v3.1",
            "Eric_v3.1", "Luca_v3.1", "Abby_v3.1", "Annie_v3.1",
            "Ava_v3.1", "Beth_v3.1", "Betty_v3.1", "Cally_v3.1",
            "Cindy_v3.1", "Donna_v3.1", "Andy_v3.1", "Brian_v3.1",
            "David_v3.1", "longanyuanfei_v3.1", "longjielidou_v3.1", "longanlingxi_v3.1",
            "longhuohuo_v3.1", "longyingtao_v3.1", "longanya_v3.1", "longwan_v3.1",
            "longxing_v3.1", "longhua_v3.1", "longhan_v3.1", "longanzhi_v3.1",
            "longzhe_v3.1", "longanyang_v3.1", "libai_v3.1", "longling_v3.1",
            "longniuniu_v3.1", "longshanshan_v3.1", "longpaopao_v3.1", "loongstella_v3.1",
            "longyuan_v3.1", "longmiao_v3.1", "longsanshu_v3.1", "longanli_v3.1",
            "longanwen_v3.1", "longanlang_v3.1", "longxiaoxia_v3.1", "longanchong_v3.1"
    );
    private static final Map<String, String> LEGACY_VOICES = Map.ofEntries(
            Map.entry("alex", "longanhuan_v3.1"),
            Map.entry("benjamin", "xunanchuan_v3.1"),
            Map.entry("charles", "xunanchuan_v3.1"),
            Map.entry("david", "longanhuan_v3.1"),
            Map.entry("anna", "longanlingxin_v3.1"),
            Map.entry("bella", "longanhuan_v3.1"),
            Map.entry("claire", "longanlingxin_v3.1"),
            Map.entry("diana", "longanhuan_v3.1"));

    private final BotSpeechProperties properties;
    private final OneBotClient oneBotClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BotSpeechService(BotSpeechProperties properties, OneBotClient oneBotClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.oneBotClient = oneBotClient;
        this.objectMapper = objectMapper;
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
     * <p>先尝试消息里直接给出的文件，再尝试 OneBot 的 get_record；音频以
     * Base64 Data URI 提交给百炼 Qwen-Audio-ASR 同步 HTTP API。</p>
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
        return transcribeWithBailian(candidates);
    }

    /**
     * 合成语音。使用阿里云百炼 Qwen-Audio-TTS WebSocket 协议。
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
        String voice = resolveVoice(requestedVoice);
        try {
            byte[] audio = synthesizeWithBailian(input, voice);
            if (audio == null || audio.length == 0) {
                log.warn("百炼语音合成返回空内容: voice={}", voice);
                return Optional.empty();
            }
            return Optional.of(audio);
        } catch (Exception ex) {
            log.warn("百炼语音合成失败: voice={}, reason={}", voice, ex.getMessage());
            return Optional.empty();
        }
    }

    /** 把旧 MOSS 音色名映射到 Qwen-Audio-TTS 音色；未知音色回退到配置的兜底音色。 */
    public String resolveVoice(String requestedVoice) {
        String value = normalizeVoiceId(blank(requestedVoice) ? properties.getFallbackVoice() : requestedVoice);
        value = LEGACY_VOICES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
        String canonical = canonicalVoice(value);
        if (canonical != null) {
            return canonical;
        }
        String fallback = normalizeVoiceId(properties.getFallbackVoice());
        fallback = LEGACY_VOICES.getOrDefault(fallback.toLowerCase(Locale.ROOT), fallback);
        String canonicalFallback = canonicalVoice(fallback);
        return canonicalFallback == null ? DEFAULT_VOICE : canonicalFallback;
    }

    private static String canonicalVoice(String value) {
        for (String voice : QWEN_VOICES) {
            if (voice.equalsIgnoreCase(value)) {
                return voice;
            }
        }
        return null;
    }

    private static String normalizeVoiceId(String value) {
        if (blank(value)) {
            return "";
        }
        String trimmed = value.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon >= 0) {
            trimmed = trimmed.substring(colon + 1);
        }
        return trimmed;
    }

    private byte[] synthesizeWithBailian(String text, String voice) throws Exception {
        String taskId = UUID.randomUUID().toString();
        String runTask = json(runTaskEvent(taskId, voice));
        String continueTask = json(continueTaskEvent(taskId, text));
        String finishTask = json(finishTaskEvent(taskId));
        TtsWebSocketListener listener = new TtsWebSocketListener(runTask, continueTask, finishTask);

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(properties.getTimeout())
                .header("Authorization", "Bearer " + properties.getTtsApiKey().trim())
                .header("user-agent", "monitor-qq-bot");
        if (!blank(properties.getTtsWorkspaceId())) {
            builder.header("X-DashScope-WorkSpace", properties.getTtsWorkspaceId().trim());
        }

        WebSocket socket = builder.buildAsync(URI.create(properties.effectiveTtsWsUrl()), listener)
                .get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            return listener.audio().get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            socket.abort();
        }
    }

    Map<String, Object> runTaskEvent(String taskId, String voice) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text_type", "PlainText");
        parameters.put("voice", voice);
        parameters.put("format", blank(properties.getResponseFormat()) ? "mp3" : properties.getResponseFormat());
        parameters.put("sample_rate", properties.getTtsSampleRate());
        parameters.put("volume", properties.getTtsVolume());
        parameters.put("rate", properties.getTtsRate());
        parameters.put("pitch", properties.getTtsPitch());
        parameters.put("enable_ssml", false);
        String format = String.valueOf(parameters.get("format"));
        if ("mp3".equalsIgnoreCase(format) || "opus".equalsIgnoreCase(format)) {
            parameters.put("bit_rate", properties.getTtsBitRate());
        }
        if (!blank(properties.getLanguageHint())) {
            parameters.put("language_hints", List.of(properties.getLanguageHint().trim()));
        }

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_group", "audio");
        payload.put("task", "tts");
        payload.put("function", "SpeechSynthesizer");
        payload.put("model", properties.getTtsModel());
        payload.put("parameters", parameters);
        payload.put("input", Map.of());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("header", header);
        event.put("payload", payload);
        return event;
    }

    static Map<String, Object> continueTaskEvent(String taskId, String text) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "continue-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("header", header);
        event.put("payload", Map.of("input", Map.of("text", text)));
        return event;
    }

    static Map<String, Object> finishTaskEvent(String taskId) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("header", header);
        event.put("payload", Map.of("input", Map.of()));
        return event;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
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

    private Optional<String> transcribeWithBailian(List<AudioCandidate> candidates) {
        String model = properties.getTranscribeModel();
        for (AudioCandidate candidate : candidates) {
            try {
                String dataUri = "data:" + candidate.contentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(candidate.bytes());
                Map<String, Object> body = transcribeRequest(candidate, dataUri);
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.effectiveAsrApiUrl()))
                        .timeout(properties.getTimeout())
                        .header("Authorization", "Bearer " + properties.effectiveBailianApiKey())
                        .header("Content-Type", "application/json")
                        .header("X-DashScope-SSE", "disable")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("语音识别失败: model={}, file={}, status={}",
                            model, candidate.filename(), response.statusCode());
                    continue;
                }
                JsonNode root = objectMapper.readTree(response.body());
                String text = root.path("output").path("sentence").path("text").asText("").trim();
                if (text.isBlank()) {
                    text = root.path("output").path("text").asText("").trim();
                }
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

    Map<String, Object> transcribeRequest(AudioCandidate candidate, String dataUri) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", audioFormat(candidate.filename()));
        if (properties.getAsrSampleRate() > 0) {
            parameters.put("sample_rate", String.valueOf(properties.getAsrSampleRate()));
        }
        if (!blank(properties.getAsrLanguageHint())) {
            parameters.put("language_hints", List.of(properties.getAsrLanguageHint().trim()));
        }
        if (properties.isKeepDialect()) {
            parameters.put("keep_dialect", true);
        }

        Map<String, Object> audio = Map.of("data", dataUri);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "input_audio");
        content.put("input_audio", audio);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(content));
        Map<String, Object> input = Map.of("messages", List.of(message));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getTranscribeModel());
        request.put("input", input);
        request.put("parameters", parameters);
        return request;
    }

    private static String audioFormat(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".amr")) return "amr";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "opus";
        if (lower.endsWith(".m4a")) return "m4a";
        if (lower.endsWith(".flac")) return "flac";
        if (lower.endsWith(".silk")) return "silk";
        return "mp3";
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

    /** 百炼 Qwen-Audio-TTS 的 WebSocket 客户端事件监听器。 */
    private final class TtsWebSocketListener implements WebSocket.Listener {

        private final String runTask;
        private final String continueTask;
        private final String finishTask;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private volatile boolean terminal;

        private TtsWebSocketListener(String runTask, String continueTask, String finishTask) {
            this.runTask = runTask;
            this.continueTask = continueTask;
            this.finishTask = finishTask;
        }

        private CompletableFuture<byte[]> audio() {
            return result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.sendText(runTask, true).whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(error);
                }
            });
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                handleEvent(webSocket, message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (terminal) {
                webSocket.request(1);
                return null;
            }
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            if (audio.size() + chunk.length > properties.getMaxAudioBytes()) {
                fail(new IllegalStateException("百炼语音合成结果超过大小上限"));
                webSocket.abort();
                return null;
            }
            audio.write(chunk, 0, chunk.length);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!terminal) {
                fail(new IllegalStateException("百炼语音合成连接提前关闭: " + statusCode + " " + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }

        private void handleEvent(WebSocket webSocket, String message) {
            try {
                JsonNode root = objectMapper.readTree(message);
                String event = root.path("header").path("event").asText("");
                switch (event) {
                    case "task-started" -> webSocket.sendText(continueTask, true)
                            .thenCompose(ignored -> webSocket.sendText(finishTask, true))
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    fail(error);
                                }
                            });
                    case "task-failed" -> {
                        String code = root.path("header").path("error_code").asText("");
                        String error = root.path("header").path("error_message").asText("未知错误");
                        fail(new IllegalStateException("百炼语音合成失败 " + code + ": " + error));
                    }
                    case "task-finished" -> {
                        if (!terminal) {
                            terminal = true;
                            result.complete(audio.toByteArray());
                        }
                    }
                    default -> {
                        // result-generated 是句子元信息；音频通过后续 binary 帧返回。
                    }
                }
            } catch (Exception ex) {
                fail(ex);
            }
        }

        private void fail(Throwable error) {
            if (!terminal) {
                terminal = true;
                result.completeExceptionally(error);
            }
        }
    }

    /** OneBot record 段中的原始引用。 */
    public record VoiceInput(String url, String file, String fileId, String path, String base64) {
        public static final VoiceInput EMPTY = new VoiceInput("", "", "", "", "");

        public boolean present() {
            return !blank(url) || !blank(file) || !blank(fileId) || !blank(path) || !blank(base64);
        }
    }

    record AudioCandidate(byte[] bytes, String filename, String contentType) {
    }
}
