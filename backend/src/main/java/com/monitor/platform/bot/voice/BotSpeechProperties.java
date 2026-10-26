package com.monitor.platform.bot.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 机器人语音能力配置。
 *
 * <p>语音识别与语音合成都走 SiliconFlow 的 OpenAI 兼容音频接口：
 * {@code POST /v1/audio/transcriptions} 与 {@code POST /v1/audio/speech}。
 * 没有配置 Key 时功能自动不可用，不影响文字、图片和命令。</p>
 *
 * <p>默认音色与语气属于运行时行为，保存在页面可配置的 {@code bot_settings} 中；
 * 这里的 {@link #fallbackVoice} 只在页面配置为空或不可用时兜底。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.speech")
public class BotSpeechProperties {

    /** 语音能力总开关。关闭后语音消息只提示发文字，也不会合成语音。 */
    private boolean enabled = false;

    /** SiliconFlow API 根地址，不要带 /v1。 */
    private String baseUrl = "https://api.siliconflow.cn";

    /** 语音接口密钥。未配置时语音识别和合成均不可用。 */
    private String apiKey;

    /** 语音转文字模型。 */
    private String transcribeModel = "XingChenAGI/XingChenASR-V3.2";

    /** 主转写模型不可用时自动回退到的兼容模型。 */
    private String fallbackTranscribeModel = "FunAudioLLM/SenseVoiceSmall";

    /** 文字转语音模型。 */
    private String ttsModel = "fnlp/MOSS-TTSD-v0.5";

    /** 页面未配置音色时使用的兜底音色。 */
    private String fallbackVoice = "fnlp/MOSS-TTSD-v0.5:alex";

    /** 语音输出格式；OneBot 推荐 mp3。 */
    private String responseFormat = "mp3";

    /** 向 OneBot 请求语音文件时转换成的格式，便于识别模型读取。 */
    private String recordFormat = "mp3";

    /** 单条语音大小上限，避免大文件打满内存。 */
    private long maxAudioBytes = 15L * 1024 * 1024;

    /** 单次转写/合成请求超时。 */
    private Duration timeout = Duration.ofSeconds(90);

    /** 单条文字转语音最多朗读多少字符；超长内容会再补发文字。 */
    private int maxTtsChars = 800;

    public boolean isTranscriptionUsable() {
        return enabled && configured() && !blank(transcribeModel);
    }

    public boolean isTtsUsable() {
        return enabled && configured() && !blank(ttsModel);
    }

    private boolean configured() {
        return !blank(apiKey) && !blank(baseUrl);
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
    public String getTtsModel() { return ttsModel; }
    public void setTtsModel(String ttsModel) { this.ttsModel = ttsModel; }
    public String getFallbackVoice() { return fallbackVoice; }
    public void setFallbackVoice(String fallbackVoice) { this.fallbackVoice = fallbackVoice; }
    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
    public String getRecordFormat() { return recordFormat; }
    public void setRecordFormat(String recordFormat) { this.recordFormat = recordFormat; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long maxAudioBytes) { this.maxAudioBytes = maxAudioBytes; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxTtsChars() { return maxTtsChars; }
    public void setMaxTtsChars(int maxTtsChars) { this.maxTtsChars = maxTtsChars; }
}