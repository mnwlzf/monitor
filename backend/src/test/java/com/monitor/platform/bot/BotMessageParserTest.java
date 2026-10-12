package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
