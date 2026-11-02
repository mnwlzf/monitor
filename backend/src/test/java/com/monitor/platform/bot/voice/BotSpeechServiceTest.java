package com.monitor.platform.bot.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BotSpeechServiceTest {

    @Test
    void shouldExtractFirstRecordSegment() throws Exception {
        var message = new ObjectMapper().readTree("""
                [{"type":"text","data":{"text":"请听"}},
                 {"type":"record","data":{"file":"voice.amr","url":"https://example.com/a.amr","file_id":"42"}}]
                """);

        BotSpeechService.VoiceInput input = BotSpeechService.extract(message);

        assertTrue(input.present());
        assertEquals("voice.amr", input.file());
        assertEquals("https://example.com/a.amr", input.url());
        assertEquals("42", input.fileId());
    }

    @Test
    void shouldExtractCqRecordFallback() {
        BotSpeechService.VoiceInput input = BotSpeechService.extract(null,
                "[CQ:record,file=voice.mp3,url=https://example.com/voice.mp3]");

        assertTrue(input.present());
        assertEquals("voice.mp3", input.file());
        assertEquals("https://example.com/voice.mp3", input.url());
    }

    @Test
    void shouldIgnoreMessageWithoutRecord() throws Exception {
        var message = new ObjectMapper().readTree("[{\"type\":\"text\",\"data\":{\"text\":\"你好\"}}]");

        assertFalse(BotSpeechService.extract(message).present());
    }

    @Test
    void shouldResolveQwenVoiceIdAndMigrateLegacyName() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setTtsModel("qwen-audio-3.1-tts-flash");
        properties.setFallbackVoice("longanhuan_v3.1");
        BotSpeechService service = new BotSpeechService(properties, mock(OneBotClient.class), new ObjectMapper());

        assertEquals("longanhuan_v3.1", service.resolveVoice("longanhuan_v3.1"));
        assertEquals("longanhuan_v3.1", service.resolveVoice("alex"));
        assertEquals("longanhuan_v3.1", service.resolveVoice("fnlp/MOSS-TTSD-v0.5:alex"));
        assertEquals("longanhuan_v3.1", service.resolveVoice(""));
        assertEquals("longanhuan_v3.1", service.resolveVoice("not-a-voice"));
        assertEquals("xunanchuan_v3.1", service.resolveVoice("xunanchuan_v3.1"));
    }

    @Test
    void shouldBuildQwenRunTaskEvent() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setTtsModel("qwen-audio-3.1-tts-flash");
        BotSpeechService service = new BotSpeechService(properties, mock(OneBotClient.class), new ObjectMapper());

        Map<String, Object> event = service.runTaskEvent("task-1", "longanhuan_v3.1");

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) event.get("header");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");
        assertEquals("run-task", header.get("action"));
        assertEquals("duplex", header.get("streaming"));
        assertEquals("audio", payload.get("task_group"));
        assertEquals("tts", payload.get("task"));
        assertEquals("SpeechSynthesizer", payload.get("function"));
        assertEquals("qwen-audio-3.1-tts-flash", payload.get("model"));
        assertEquals("PlainText", parameters.get("text_type"));
        assertEquals("longanhuan_v3.1", parameters.get("voice"));
        assertEquals("mp3", parameters.get("format"));
        assertEquals(22050, parameters.get("sample_rate"));
        assertEquals(List.of("zh"), parameters.get("language_hints"));
    }

    @Test
    void shouldShareBailianKeyBetweenAsrAndTts() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setEnabled(true);
        properties.setAsrApiKey("bailian-key");
        assertTrue(properties.isTranscriptionUsable());
        assertTrue(properties.isTtsUsable());

        properties.setAsrApiKey("");
        properties.setTtsApiKey("bailian-key");
        assertTrue(properties.isTranscriptionUsable());
        assertTrue(properties.isTtsUsable());
    }

    @Test
    void shouldBuildWorkspaceAsrAndTtsUrls() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setTtsWorkspaceId("ws-123");

        assertEquals("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                properties.effectiveTtsWsUrl());
        assertEquals("https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                properties.effectiveAsrApiUrl());
    }

    @Test
    void shouldBuildQwenAsrRequestWithBase64Audio() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setEnabled(true);
        properties.setAsrApiKey("bailian-key");
        BotSpeechService service = new BotSpeechService(properties, mock(OneBotClient.class), new ObjectMapper());
        BotSpeechService.AudioCandidate candidate = new BotSpeechService.AudioCandidate(
                new byte[]{1, 2, 3}, "voice.mp3", "audio/mpeg");

        Map<String, Object> request = service.transcribeRequest(candidate, "data:audio/mpeg;base64,AQID");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) request.get("input");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) input.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputAudio = (Map<String, Object>) content.get(0).get("input_audio");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) request.get("parameters");
        assertEquals("qwen-audio-3.1-asr-flash", request.get("model"));
        assertEquals("data:audio/mpeg;base64,AQID", inputAudio.get("data"));
        assertEquals("mp3", parameters.get("format"));
        assertEquals(List.of("zh"), parameters.get("language_hints"));
    }

    @Test
    void shouldSanitizeMarkdownForSpeech() {
        String text = """
                ## 查询结果
                | 账号 | 余额 |
                |---|---|
                | A | 12.5 |
                **已确认**
                """;

        String spoken = BotSpeechService.sanitizeForSpeech(text, 800);

        assertTrue(spoken.contains("查询结果"));
        assertTrue(spoken.contains("账号，余额"));
        assertFalse(spoken.contains("---"));
        assertFalse(spoken.contains("**"));
    }

    @Test
    void shouldLimitSpokenText() {
        String spoken = BotSpeechService.sanitizeForSpeech("一".repeat(200), 50);

        assertTrue(spoken.length() <= 51);
    }
}
