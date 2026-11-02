package com.monitor.platform.bot.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceReplyIntentTest {

    @Test
    void shouldRecognizeSimpleVoiceReply() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("语音回复");

        assertTrue(intent.requested());
        assertEquals("", intent.cleanedText());
        assertNull(intent.style());
        assertNull(intent.voice());
    }

    @Test
    void shouldExtractStyleAndRemoveDirective() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("讲个笑话，用温柔的语气语音回复");

        assertTrue(intent.requested());
        assertEquals("温柔", intent.style());
        assertEquals("讲个笑话", intent.cleanedText());
    }

    @Test
    void shouldExtractQwenVoiceName() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("用 longanhuan_v3.1 音色语音回复");

        assertTrue(intent.requested());
        assertEquals("longanhuan_v3.1", intent.voice());
        assertNull(intent.style());
    }

    @Test
    void shouldExtractQwenVoiceId() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("用 qwen-audio-3.1-tts-flash:longanhuan_v3.1 音色语音回复");

        assertTrue(intent.requested());
        assertEquals("qwen-audio-3.1-tts-flash:longanhuan_v3.1", intent.voice());
    }

    @Test
    void shouldFindStyleAfterSimpleDirective() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("语音回复，用温柔的语气");

        assertTrue(intent.requested());
        assertEquals("温柔", intent.style());
    }

    @Test
    void shouldRespectNegativeInstruction() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("不要语音回复，直接发文字");

        assertFalse(intent.requested());
        assertEquals("不要语音回复，直接发文字", intent.cleanedText());
    }

    @Test
    void shouldIgnoreOrdinaryChat() {
        VoiceReplyIntent intent = VoiceReplyIntent.parse("今天天气怎么样");

        assertFalse(intent.requested());
    }
}
