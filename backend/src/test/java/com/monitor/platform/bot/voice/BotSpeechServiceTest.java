package com.monitor.platform.bot.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.junit.jupiter.api.Test;

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
    void shouldResolveMossVoiceId() {
        BotSpeechProperties properties = new BotSpeechProperties();
        properties.setTtsModel("fnlp/MOSS-TTSD-v0.5");
        properties.setFallbackVoice("fnlp/MOSS-TTSD-v0.5:alex");
        BotSpeechService service = new BotSpeechService(properties, mock(OneBotClient.class));

        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", service.resolveVoice("alex"));
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", service.resolveVoice("fnlp/MOSS-TTSD-v0.5:alex"));
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", service.resolveVoice(""));
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", service.resolveVoice("not-a-voice"));
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
