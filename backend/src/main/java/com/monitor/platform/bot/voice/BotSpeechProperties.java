package com.monitor.platform.bot.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 机器人语音能力配置。
 *
 * <p>语音识别和语音合成均使用阿里云百炼：ASR 走同步 HTTP API，
 * TTS 走 Qwen-Audio-TTS WebSocket 实时接口。两者共用百炼工作空间和 API Key，
 * 也支持分别覆盖地址与 Key；未配置时对应能力自动不可用。</p>
 *
 * <p>默认音色与语气属于运行时行为，保存在页面可配置的 {@code bot_settings} 中；
 * 这里的 {@link #fallbackVoice} 只在页面配置为空或不可用时兜底。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.speech")
public class BotSpeechProperties {

    private static final String DEFAULT_TTS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final String DEFAULT_ASR_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /** 语音能力总开关。关闭后语音消息只提示发文字，也不会合成语音。 */
    private boolean enabled = false;

    /** 百炼 ASR HTTP 地址；留空时按工作空间和地域生成。 */
    private String asrApiUrl;

    /** ASR 单独使用的百炼工作空间 ID；留空时复用 TTS 工作空间。 */
    private String asrWorkspaceId;

    /** ASR 单独使用的百炼 API Key；留空时复用 TTS API Key。 */
    private String asrApiKey;

    /** 语音转文字模型。 */
    private String transcribeModel = "qwen-audio-3.1-asr-flash";

    /** 识别语言提示；留空时由模型自动识别。 */
    private String asrLanguageHint = "zh";

    /** ASR 音频采样率；0 表示不传，由服务端根据音频自动处理。 */
    private int asrSampleRate = 0;

    /** 是否保留方言表达；false 时转写为普通话文本。 */
    private boolean keepDialect = false;

    /**
     * 百炼 Qwen-Audio-TTS WebSocket 地址。
     *
     * <p>留空时优先按工作空间 ID 和地域生成业务空间专属域名；
     * 未配置工作空间时使用百炼兼容地址。</p>
     */
    private String ttsWsUrl;

    /** 百炼业务空间 ID，用于生成专属域名和请求头。 */
    private String ttsWorkspaceId;

    /** 百炼地域，默认华北 2（北京）。 */
    private String ttsRegion = "cn-beijing";

    /** 百炼 API Key；ASR/TTS 未单独配置 Key 时共用。 */
    private String ttsApiKey;

    /** 文字转语音模型。 */
    private String ttsModel = "qwen-audio-3.1-tts-flash";

    /** 页面未配置音色时使用的兜底音色。 */
    private String fallbackVoice = "longanhuan_v3.1";

    /** 语音输出格式；OneBot 推荐 mp3。 */
    private String responseFormat = "mp3";

    /** 百炼 TTS 采样率。 */
    private int ttsSampleRate = 22050;

    /** 百炼 TTS 音量，范围 0-100。 */
    private int ttsVolume = 50;

    /** 百炼 TTS 语速，范围 0.5-2.0。 */
    private double ttsRate = 1.0;

    /** 百炼 TTS 音调，范围 0.5-2.0。 */
    private double ttsPitch = 1.0;

    /** 百炼 TTS MP3/Opus 码率，单位 kbps。 */
    private int ttsBitRate = 32;

    /** 合成语言提示，例如 zh / en；留空时不传。 */
    private String languageHint = "zh";

    /** 向 OneBot 请求语音文件时转换成的格式，便于识别模型读取。 */
    private String recordFormat = "mp3";

    /** 单条语音大小上限，避免大文件打满内存。 */
    private long maxAudioBytes = 15L * 1024 * 1024;

    /** 单次转写/合成请求超时。 */
    private Duration timeout = Duration.ofSeconds(90);

    /** 单条文字转语音最多朗读多少字符；超长内容会再补发文字。 */
    private int maxTtsChars = 800;

    public boolean isTranscriptionUsable() {
        return enabled && !blank(effectiveBailianApiKey())
                && !blank(effectiveAsrApiUrl()) && !blank(transcribeModel);
    }

    public boolean isTtsUsable() {
        return enabled && !blank(effectiveBailianApiKey())
                && !blank(effectiveTtsWsUrl()) && !blank(ttsModel);
    }

    /** 实际使用的百炼 ASR HTTP 地址。 */
    public String effectiveAsrApiUrl() {
        String configured = asrApiUrl == null ? "" : asrApiUrl.trim();
        String workspace = effectiveWorkspaceId();
        if (!blank(configured)) {
            if (!blank(workspace)) {
                configured = configured
                        .replace("{WorkspaceId}", workspace)
                        .replace("{workspaceId}", workspace);
            }
            return configured;
        }
        if (!blank(workspace)) {
            return "https://" + workspace + "." + region()
                    + ".maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
        }
        return DEFAULT_ASR_URL;
    }

    /** 实际使用的百炼 TTS WebSocket 地址。 */
    public String effectiveTtsWsUrl() {
        String configured = ttsWsUrl == null ? "" : ttsWsUrl.trim();
        String workspace = effectiveWorkspaceId();
        if (!blank(configured)) {
            if (!blank(workspace)) {
                configured = configured
                        .replace("{WorkspaceId}", workspace)
                        .replace("{workspaceId}", workspace);
            }
            return configured;
        }
        if (!blank(workspace)) {
            return "wss://" + workspace + "." + region()
                    + ".maas.aliyuncs.com/api-ws/v1/inference";
        }
        return DEFAULT_TTS_URL;
    }

    /** ASR/TTS 共用的百炼 API Key。 */
    public String effectiveBailianApiKey() {
        return !blank(asrApiKey) ? asrApiKey.trim()
                : (ttsApiKey == null ? "" : ttsApiKey.trim());
    }

    /** ASR/TTS 共用的业务空间 ID。 */
    public String effectiveWorkspaceId() {
        if (!blank(asrWorkspaceId)) {
            return asrWorkspaceId.trim();
        }
        return ttsWorkspaceId == null ? "" : ttsWorkspaceId.trim();
    }

    private String region() {
        return blank(ttsRegion) ? "cn-beijing" : ttsRegion.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAsrApiUrl() { return asrApiUrl; }
    public void setAsrApiUrl(String asrApiUrl) { this.asrApiUrl = asrApiUrl; }
    public String getAsrWorkspaceId() { return asrWorkspaceId; }
    public void setAsrWorkspaceId(String asrWorkspaceId) { this.asrWorkspaceId = asrWorkspaceId; }
    public String getAsrApiKey() { return asrApiKey; }
    public void setAsrApiKey(String asrApiKey) { this.asrApiKey = asrApiKey; }
    public String getTranscribeModel() { return transcribeModel; }
    public void setTranscribeModel(String transcribeModel) { this.transcribeModel = transcribeModel; }
    public String getAsrLanguageHint() { return asrLanguageHint; }
    public void setAsrLanguageHint(String asrLanguageHint) { this.asrLanguageHint = asrLanguageHint; }
    public int getAsrSampleRate() { return asrSampleRate; }
    public void setAsrSampleRate(int asrSampleRate) { this.asrSampleRate = asrSampleRate; }
    public boolean isKeepDialect() { return keepDialect; }
    public void setKeepDialect(boolean keepDialect) { this.keepDialect = keepDialect; }
    public String getTtsWsUrl() { return ttsWsUrl; }
    public void setTtsWsUrl(String ttsWsUrl) { this.ttsWsUrl = ttsWsUrl; }
    public String getTtsWorkspaceId() { return ttsWorkspaceId; }
    public void setTtsWorkspaceId(String ttsWorkspaceId) { this.ttsWorkspaceId = ttsWorkspaceId; }
    public String getTtsRegion() { return ttsRegion; }
    public void setTtsRegion(String ttsRegion) { this.ttsRegion = ttsRegion; }
    public String getTtsApiKey() { return ttsApiKey; }
    public void setTtsApiKey(String ttsApiKey) { this.ttsApiKey = ttsApiKey; }
    public String getTtsModel() { return ttsModel; }
    public void setTtsModel(String ttsModel) { this.ttsModel = ttsModel; }
    public String getFallbackVoice() { return fallbackVoice; }
    public void setFallbackVoice(String fallbackVoice) { this.fallbackVoice = fallbackVoice; }
    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
    public int getTtsSampleRate() { return ttsSampleRate; }
    public void setTtsSampleRate(int ttsSampleRate) { this.ttsSampleRate = ttsSampleRate; }
    public int getTtsVolume() { return ttsVolume; }
    public void setTtsVolume(int ttsVolume) { this.ttsVolume = ttsVolume; }
    public double getTtsRate() { return ttsRate; }
    public void setTtsRate(double ttsRate) { this.ttsRate = ttsRate; }
    public double getTtsPitch() { return ttsPitch; }
    public void setTtsPitch(double ttsPitch) { this.ttsPitch = ttsPitch; }
    public int getTtsBitRate() { return ttsBitRate; }
    public void setTtsBitRate(int ttsBitRate) { this.ttsBitRate = ttsBitRate; }
    public String getLanguageHint() { return languageHint; }
    public void setLanguageHint(String languageHint) { this.languageHint = languageHint; }
    public String getRecordFormat() { return recordFormat; }
    public void setRecordFormat(String recordFormat) { this.recordFormat = recordFormat; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long maxAudioBytes) { this.maxAudioBytes = maxAudioBytes; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxTtsChars() { return maxTtsChars; }
    public void setMaxTtsChars(int maxTtsChars) { this.maxTtsChars = maxTtsChars; }
}
