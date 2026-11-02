package com.monitor.platform.bot.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 机器人语音能力配置。
 *
 * <p>语音识别继续走 SiliconFlow 的 OpenAI 兼容
 * {@code POST /v1/audio/transcriptions}；语音合成走阿里云百炼
 * Qwen-Audio-TTS 的 WebSocket 实时接口。两部分使用独立地址和 Key，
 * 未配置时对应能力自动不可用，不影响文字、图片和命令。</p>
 *
 * <p>默认音色与语气属于运行时行为，保存在页面可配置的 {@code bot_settings} 中；
 * 这里的 {@link #fallbackVoice} 只在页面配置为空或不可用时兜底。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.speech")
public class BotSpeechProperties {

    private static final String DEFAULT_TTS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    /** 语音能力总开关。关闭后语音消息只提示发文字，也不会合成语音。 */
    private boolean enabled = false;

    /** SiliconFlow 语音识别 API 根地址，不要带 /v1。 */
    private String baseUrl = "https://api.siliconflow.cn";

    /** SiliconFlow 语音识别密钥。未配置时语音识别不可用。 */
    private String apiKey;

    /** 语音转文字模型。 */
    private String transcribeModel = "XingChenAGI/XingChenASR-V3.2";

    /** 主转写模型不可用时自动回退到的兼容模型。 */
    private String fallbackTranscribeModel = "FunAudioLLM/SenseVoiceSmall";

    /**
     * 百炼 Qwen-Audio-TTS WebSocket 地址。
     *
     * <p>留空时优先按 {@link #ttsWorkspaceId} 和 {@link #ttsRegion} 生成业务空间专属域名；
     * 两者都没配置时使用百炼兼容地址 {@code wss://dashscope.aliyuncs.com/api-ws/v1/inference}。</p>
     */
    private String ttsWsUrl;

    /** 百炼业务空间 ID，用于生成专属 WebSocket 域名和请求头。 */
    private String ttsWorkspaceId;

    /** 百炼地域，默认华北 2（北京）。 */
    private String ttsRegion = "cn-beijing";

    /** 百炼 API Key；与 SiliconFlow 识别 Key 独立。 */
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
        return enabled && !blank(apiKey) && !blank(baseUrl) && !blank(transcribeModel);
    }

    public boolean isTtsUsable() {
        return enabled && !blank(ttsApiKey) && !blank(effectiveTtsWsUrl()) && !blank(ttsModel);
    }

    /** 实际使用的百炼 TTS WebSocket 地址。 */
    public String effectiveTtsWsUrl() {
        String configured = ttsWsUrl == null ? "" : ttsWsUrl.trim();
        if (!blank(configured)) {
            if (!blank(ttsWorkspaceId)) {
                configured = configured
                        .replace("{WorkspaceId}", ttsWorkspaceId.trim())
                        .replace("{workspaceId}", ttsWorkspaceId.trim());
            }
            return configured;
        }
        if (!blank(ttsWorkspaceId)) {
            String region = blank(ttsRegion) ? "cn-beijing" : ttsRegion.trim();
            return "wss://" + ttsWorkspaceId.trim() + "." + region
                    + ".maas.aliyuncs.com/api-ws/v1/inference";
        }
        return DEFAULT_TTS_URL;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getTranscribeModel() { return transcribeModel; }
    public void setTranscribeModel(String transcribeModel) { this.transcribeModel = transcribeModel; }
    public String getFallbackTranscribeModel() { return fallbackTranscribeModel; }
    public void setFallbackTranscribeModel(String fallbackTranscribeModel) { this.fallbackTranscribeModel = fallbackTranscribeModel; }
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
