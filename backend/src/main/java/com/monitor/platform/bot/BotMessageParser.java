package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.vision.BotImageFetcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OneBot 消息解析。
 *
 * <p>把「消息段」翻译成机器人处理链需要的几样东西：纯文本、是否真的 @ 了机器人、
 * 被引用消息的原文、拼给模型的提示词，以及超长回复的截断。</p>
 *
 * <p>从 {@code BotMessageService} 抽出：这些是纯解析/格式化，和「处理流程」无关，
 * 单独放一处也便于写测试。</p>
 */
@Component
public class BotMessageParser {

    /** 引用内容只作为上下文，太长就截断，避免把提示词撑爆。 */
    private static final int QUOTED_MAX_LENGTH = 300;

    /** 用户只发图片、没带文字时的提示词。 */
    private static final String IMAGE_ONLY_PROMPT = "这是我发来的图片，请描述并回应。";

    private final OneBotClient client;

    public BotMessageParser(OneBotClient client) {
        this.client = client;
    }

    /**
     * 被引用消息的内容：文字 + 其中的图片。
     *
     * @param text   拼好的纯文本（图片段用 {@code [图片]} 占位）
     * @param images 消息里的图片引用，交给调用方下载后作为多模态附件送给模型
     */
    public record QuotedMessage(String text, List<BotImageFetcher.ImageRef> images) {

        public static final QuotedMessage EMPTY = new QuotedMessage("", List.of());

        public boolean isEmpty() {
            return text.isBlank() && images.isEmpty();
        }
    }

    /**
     * 引用（回复）消息的内容。
     *
     * <p>QQ 的引用在消息段里只有一个 {@code reply} 段，<strong>通常只带消息 id、不带正文</strong>，
     * 所以用户引用一条消息再问「这个是什么」时模型完全是瞎的。这里按两步取：</p>
     * <ol>
     *     <li>段里自带 {@code text} / {@code content}（部分实现会带）就直接用；</li>
     *     <li>否则拿 id 调 OneBot 的 {@code get_msg} 把原文取回来。</li>
     * </ol>
     *
     * <p><strong>被引用的消息可能是图片</strong>（「引用这张图，总结一下」）。所以取回来的是整个
     * message 节点：文字照旧当上下文，图片交给调用方下载后送给模型 ——
     * 只给模型一句「[图片]」它是看不到内容的。</p>
     *
     * <p>取不到就返回 {@link QuotedMessage#EMPTY} —— 少一段上下文不该让整条回复失败。</p>
     */
    public QuotedMessage quotedMessage(OneBotEvent event) {
        JsonNode message = event.message();
        if (message == null || !message.isArray()) {
            return QuotedMessage.EMPTY;
        }
        for (JsonNode segment : message) {
            if (!"reply".equals(segment.path("type").asText())) {
                continue;
            }
            JsonNode data = segment.path("data");
            String inline = data.path("text").asText("");
            if (inline.isBlank()) {
                inline = data.path("content").asText("");
            }
            if (!inline.isBlank()) {
                return new QuotedMessage(clip(inline), List.of());
            }
            String id = data.path("id").asText("");
            if (!id.isBlank()) {
                JsonNode quoted = client.fetchMessage(id);
                if (quoted == null || quoted.isMissingNode() || quoted.isNull()) {
                    return QuotedMessage.EMPTY;
                }
                return new QuotedMessage(clip(OneBotClient.textOf(quoted)), BotImageFetcher.extract(quoted));
            }
        }
        return QuotedMessage.EMPTY;
    }

    /** 只要被引用消息的文字（不需要图片时的简写）。 */
    public String quotedText(OneBotEvent event) {
        return quotedMessage(event).text();
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= QUOTED_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, QUOTED_MAX_LENGTH) + "…";
    }

    /**
     * 组装发给模型的用户消息：把引用内容放在前面当上下文。
     *
     * <p>只引用、没打字时也要有话说 —— 以前这种情况会被判成「空消息」直接丢掉。</p>
     */
    public static String buildPrompt(String text, String quoted, boolean hasImages) {
        return buildPrompt(text, quoted, hasImages, false);
    }

    /**
     * 组装提示词。
     *
     * @param hasImages       本次请求是否带了图片附件（自己发的 + 引用的）
     * @param quotedHasImages 引用的那条消息里的图片是否已经作为附件带上
     */
    public static String buildPrompt(String text, String quoted, boolean hasImages, boolean quotedHasImages) {
        StringBuilder sb = new StringBuilder();
        if (!quoted.isBlank()) {
            sb.append("（用户引用了这条消息：").append(quoted);
            if (quotedHasImages) {
                sb.append("；被引用消息里的图片已作为附件发给你");
            }
            sb.append("）").append('\n');
        } else if (quotedHasImages) {
            sb.append("（用户引用了上面这条消息，其中的图片已作为附件发给你）").append('\n');
        }
        if (text.isBlank()) {
            if (quotedHasImages) {
                sb.append("请直接看引用的图片并回应。");
            } else {
                sb.append(hasImages ? IMAGE_ONLY_PROMPT : "用户只引用了上面这条消息，没有额外文字，请自然地回应。");
            }
        } else {
            sb.append(text);
        }
        return sb.toString();
    }    /** 只取文本段，@ 机器人、图片等非文本段自然被丢掉。 */
    public String extractText(OneBotEvent event) {
        JsonNode message = event.message();
        if (message != null && message.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode segment : message) {
                if ("text".equals(segment.path("type").asText())) {
                    sb.append(segment.path("data").path("text").asText(""));
                }
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        String raw = event.rawMessage();
        if (raw == null) {
            return "";
        }
        // post_format=string 时 message 是字符串，这里把 CQ 码去掉
        return raw.replaceAll("\\[CQ:[^]]*]", " ").replaceAll("\\s+", " ").trim();
    }

    public boolean mentioned(OneBotEvent event) {
        JsonNode message = event.message();
        if (message != null && message.isArray()) {
            for (JsonNode segment : message) {
                if ("at".equals(segment.path("type").asText())
                        && String.valueOf(event.selfId()).equals(segment.path("data").path("qq").asText())) {
                    return true;
                }
            }
        }
        String raw = event.rawMessage();
        return raw != null && event.selfId() != null && raw.contains("[CQ:at,qq=" + event.selfId());
    }

    public String truncate(String text, int maxReplyLength) {
        if (text == null) {
            return "";
        }
        int max = Math.max(50, maxReplyLength);
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断）";
    }
}
