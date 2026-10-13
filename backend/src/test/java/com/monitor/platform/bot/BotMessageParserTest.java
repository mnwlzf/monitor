package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OneBot 消息解析。
 *
 * <p>其中「是否 @ 了机器人」是线上踩过的坑：用户手动打的「@机器人」只是文本段，
 * 不是 QQ 的 at 段，按规则会被静默忽略。这里把两种情况的判定固定下来。</p>
 */
class BotMessageParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BotMessageParser parser = new BotMessageParser(mock(OneBotClient.class));

    private OneBotEvent event(String json) throws Exception {
        return mapper.readValue(json, OneBotEvent.class);
    }

    @Test
    void extractsOnlyTextSegments() throws Exception {
        OneBotEvent event = event("""
                {"post_type":"message","message_type":"group","self_id":1,"user_id":2,
                 "message":[{"type":"at","data":{"qq":"1"}},
                            {"type":"text","data":{"text":"  你好  "}}]}
                """);

        assertThat(parser.extractText(event)).isEqualTo("你好");
    }

    @Test
    void countsOnlyRealAtSegmentAsMention() throws Exception {
        // 真 @：at 段 + 机器人自己的 QQ
        assertThat(parser.mentioned(event("""
                {"post_type":"message","message_type":"group","self_id":3097553108,"user_id":2,
                 "raw_message":"[CQ:at,qq=3097553108] 你好",
                 "message":[{"type":"at","data":{"qq":"3097553108"}},
                            {"type":"text","data":{"text":" 你好"}}]}
                """))).isTrue();

        // 纯文本的「@机器人」不是 at 段 —— 线上「吞消息」就是这种情况
        assertThat(parser.mentioned(event("""
                {"post_type":"message","message_type":"group","self_id":3097553108,"user_id":2,
                 "raw_message":"@碧云天 你好",
                 "message":[{"type":"text","data":{"text":"@碧云天 你好"}}]}
                """))).isFalse();
    }

    @Test
    void buildsPromptWithQuotedContext() {
        assertThat(BotMessageParser.buildPrompt("你好", "原消息", false))
                .contains("原消息")
                .contains("你好");
    }

    @Test
    void truncatesLongRepliesOnly() {
        assertThat(parser.truncate("x".repeat(2000), 100)).endsWith("…（已截断）");
        assertThat(parser.truncate("短", 100)).isEqualTo("短");
    }
    @Test
    void readsQuotedImageMessageWithImageRefs() throws Exception {
        OneBotClient client = mock(OneBotClient.class);
        JsonNode quotedMessage = mapper.readTree("""
                [{"type":"image","data":{"file":"a.jpg","url":"https://example.com/a.png"}}]
                """);
        when(client.fetchMessage("123")).thenReturn(quotedMessage);
        BotMessageParser parser = new BotMessageParser(client);

        BotMessageParser.QuotedMessage quoted = parser.quotedMessage(event("""
                {"post_type":"message","message_type":"group","self_id":1,"user_id":2,
                 "message":[{"type":"reply","data":{"id":"123"}},
                            {"type":"text","data":{"text":"总结图片内容"}}]}
                """));

        // 文字段用 [图片] 占位，真正的图片以引用形式交给调用方下载
        assertThat(quoted.text()).isEqualTo("[图片]");
        assertThat(quoted.images()).hasSize(1);
        assertThat(quoted.images().get(0).url()).isEqualTo("https://example.com/a.png");
    }

    @Test
    void keepsInlineQuotedTextWithoutCallingOneBot() throws Exception {
        OneBotClient client = mock(OneBotClient.class);
        BotMessageParser parser = new BotMessageParser(client);

        BotMessageParser.QuotedMessage quoted = parser.quotedMessage(event("""
                {"post_type":"message","message_type":"group","self_id":1,"user_id":2,
                 "message":[{"type":"reply","data":{"text":"被引用的原文"}}]}
                """));

        assertThat(quoted.text()).isEqualTo("被引用的原文");
        assertThat(quoted.images()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void returnsEmptyQuotedMessageWhenFetchFails() throws Exception {
        OneBotClient client = mock(OneBotClient.class);
        when(client.fetchMessage("404")).thenReturn(null);
        BotMessageParser parser = new BotMessageParser(client);

        BotMessageParser.QuotedMessage quoted = parser.quotedMessage(event("""
                {"post_type":"message","message_type":"group","self_id":1,"user_id":2,
                 "message":[{"type":"reply","data":{"id":"404"}}]}
                """));

        assertThat(quoted.isEmpty()).isTrue();
    }

    @Test
    void promptTellsModelThatQuotedImagesAreAttached() {
        assertThat(BotMessageParser.buildPrompt("总结图片内容", "[图片]", true, true))
                .contains("被引用消息里的图片已作为附件发给你")
                .contains("总结图片内容");
        // 只引用图片、没打字时也要说清楚「看的是引用的图」
        assertThat(BotMessageParser.buildPrompt("", "[图片]", true, true))
                .contains("请直接看引用的图片并回应");
    }
}