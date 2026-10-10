package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QQ 消息处理：白名单 → @/前缀判断 → 命令或大模型 → 回消息。
 *
 * <p>白名单、命令前缀、回复长度、记忆窗口等全部读 {@link BotSettingsService} 的**内存快照**，
 * 页面上改完立即生效，不需要重启。只有「部署级接线参数」（webhook 密钥、OneBot 地址、
 * 总开关）仍在环境变量里。</p>
 *
 * <p>回调本身必须尽快返回（OneBot 有超时），所以真正的处理丢到后台线程里做。</p>
 */
@Service
public class BotMessageService {

    private static final Logger log = LoggerFactory.getLogger(BotMessageService.class);

    private static final String SYSTEM_PROMPT = """
            你是「上游账号监控」系统的运维助手，通过 QQ 回答用户关于平台、账号、号池的问题。
            要求：
            1. 只依据工具返回的真实数据回答，绝不编造数字；
            2. 用简体中文，直接给结论；列表用「- 」开头，不要输出 Markdown 表格或代码块；
            3. 涉及指标时说明时间窗（例如「近 24 小时」）；
            4. 工具查不到就直说查不到，不要猜测。
            """;

    private final BotSettingsService settingsService;
    private final OneBotClient client;
    private final MonitorChatTools tools;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "qq-bot");
        thread.setDaemon(true);
        return thread;
    });

    /** 懒构建并缓存；记忆窗口变了会重建。 */
    private volatile ChatClient chatClient;
    private volatile int chatClientWindow = -1;
    private volatile boolean chatClientUnavailable;

    public BotMessageService(BotSettingsService settingsService,
                             OneBotClient client,
                             MonitorChatTools tools,
                             ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.settingsService = settingsService;
        this.client = client;
        this.tools = tools;
        this.chatClientBuilder = chatClientBuilder;
    }

    /** 入口：OneBot 反向 HTTP 推过来的一条事件。 */
    public void onEvent(OneBotEvent event) {
        if (event == null || !"message".equals(event.postType())) {
            return;
        }
        BotSettings settings = settingsService.current();
        if (!settings.enabled()) {
            log.debug("机器人运行时开关已关闭，忽略消息");
            return;
        }
        boolean group = "group".equals(event.messageType());
        boolean privateChat = "private".equals(event.messageType());
        if (!group && !privateChat) {
            return;
        }
        if (!allowed(event, group, settings)) {
            log.debug("忽略非白名单会话: group={}, user={}", event.groupId(), event.userId());
            return;
        }
        if (group && settings.requireMention() && !mentioned(event)) {
            return;
        }
        String text = extractText(event);
        if (text.isBlank()) {
            return;
        }
        executor.submit(() -> {
            String reply;
            try {
                reply = answer(event, text, settings);
            } catch (Exception ex) {
                log.warn("处理 QQ 消息失败: {}", ex.getMessage());
                reply = "处理这条消息时出错了：" + ex.getMessage();
            }
            send(event, group, truncate(reply, settings.maxReplyLength()));
        });
    }

    private boolean allowed(OneBotEvent event, boolean group, BotSettings settings) {
        return group ? settings.isGroupAllowed(event.groupId())
                : settings.isUserAllowed(event.userId());
    }

    private String answer(OneBotEvent event, String text, BotSettings settings) {
        String prefix = settings.commandPrefix();
        if (prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return command(text.substring(prefix.length()).trim());
        }
        return askModel(event, text, settings);
    }

    /** 确定性命令：没配大模型也能用。 */
    private String command(String commandLine) {
        String[] parts = commandLine.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (name) {
            case "help", "帮助", "?" -> help();
            case "platform", "平台", "overview" -> tools.platformOverview();
            case "pool", "号池" -> tools.poolOverview(arg);
            case "ingest", "采集" -> tools.poolIngestStatus();
            case "account", "账号" -> tools.accountDetail(arg);
            case "changes", "变更" -> tools.recentChanges(parseInt(arg, 5));
            default -> "未知命令：" + name + "\n" + help();
        };
    }

    private String askModel(OneBotEvent event, String text, BotSettings settings) {
        ChatClient client = chatClient(settings);
        if (client == null) {
            return "自然语言问答未启用（需要在配置里打开大模型）。当前可用命令：\n" + help();
        }
        return client.prompt()
                .user(text)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId(event)))
                .call()
                .content();
    }

    private ChatClient chatClient(BotSettings settings) {
        if (chatClientUnavailable) {
            return null;
        }
        int window = Math.max(2, settings.memoryWindow());
        ChatClient cached = chatClient;
        if (cached != null && chatClientWindow == window) {
            return cached;
        }
        synchronized (this) {
            if (chatClient != null && chatClientWindow == window) {
                return chatClient;
            }
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                chatClientUnavailable = true;
                log.info("未配置大模型，QQ 机器人只支持命令（/help 查看）");
                return null;
            }
            chatClient = builder
                    .defaultSystem(SYSTEM_PROMPT)
                    .defaultTools(tools)
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
                            MessageWindowChatMemory.builder().maxMessages(window).build())
                            .build())
                    .build();
            chatClientWindow = window;
            log.info("QQ 机器人已接入大模型，支持自然语言问答（记忆窗口 {} 条）", window);
            return chatClient;
        }
    }

    private String conversationId(OneBotEvent event) {
        return "group".equals(event.messageType())
                ? "qq-group-" + event.groupId()
                : "qq-user-" + event.userId();
    }

    private void send(OneBotEvent event, boolean group, String reply) {
        if (reply == null || reply.isBlank()) {
            reply = "（没有查到内容）";
        }
        if (group) {
            client.sendGroupMessage(event.groupId(), reply);
        } else {
            client.sendPrivateMessage(event.userId(), reply);
        }
    }

    /** 只取文本段，@ 机器人、图片等非文本段自然被丢掉。 */
    private String extractText(OneBotEvent event) {
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

    private boolean mentioned(OneBotEvent event) {
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

    private String truncate(String text, int maxReplyLength) {
        if (text == null) {
            return "";
        }
        int max = Math.max(50, maxReplyLength);
        return text.length() <= max ? text : text.substring(0, max) + "…（已截断）";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String help() {
        return """
                可用命令：
                - /平台            上游平台概览
                - /号池 [时间窗]   号池账号的请求数、缓存率、首 Token（默认 24h）
                - /采集            号池直连库增量采集状态
                - /账号 <关键字>   按名称查账号余额与状态
                - /变更 [条数]     最近的账号/密钥变更
                也可以直接说人话，例如「近 7 天哪个号池账号缓存率最低？」""";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}