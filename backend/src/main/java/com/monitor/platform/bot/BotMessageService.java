package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.BotReportRenderer;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * QQ 消息处理：白名单 → @/前缀判断 → 身份识别 → 命令或大模型 → 回消息。
 *
 * <p>白名单、命令前缀、回复长度、记忆窗口等全部读 {@link BotSettingsService} 的**内存快照**，
 * 页面上改完立即生效，不需要重启。只有「部署级接线参数」（webhook 密钥、OneBot 地址、
 * 总开关）仍在环境变量里。</p>
 *
 * <p>回调本身必须尽快返回（OneBot 有超时），所以真正的处理丢到后台线程里做。</p>
 *
 * <h2>身份与人设</h2>
 * 每条消息先由 {@link BotIdentityResolver} 解析发送者身份，再决定用哪套人设：
 * <ul>
 *     <li><b>平台管理员</b>：监控助手 + 全部只读查询工具；</li>
 *     <li><b>平台普通用户</b>：监控助手，但<strong>不挂任何平台级查询工具</strong>，
 *         问到平台数据时只回「仅管理员可查」；</li>
 *     <li><b>非平台用户</b>：纯聊天机器人，<strong>绝不能出现任何平台相关信息</strong>。</li>
 * </ul>
 *
 * <h2>回复形态</h2>
 * 命令与模型回答统一产出 {@link BotReport}：内容不长就发纯文本，
 * 超过 {@code maxReplyLength} 就渲染成表格 / 热力图图片（见 {@link BotReportRenderer}），
 * 避免被截断或排成难读的项目符号列表。渲染或发送失败时退回截断文本。
 */
@Service
public class BotMessageService {

    private static final Logger log = LoggerFactory.getLogger(BotMessageService.class);

    /** 邮箱格式校验：够用即可，不做 RFC 级校验。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final String MONITOR_ADMIN_PROMPT = """
            你是「上游账号监控」系统的运维助手，通过 QQ 回答用户关于平台、账号、号池的问题。
            当前对话者是平台管理员，可以查看全部平台数据。
            要求：
            1. 只依据工具返回的真实数据回答，绝不编造数字；
            2. 用简体中文，直接给结论；
            3. 需要罗列多条数据时用 Markdown 表格（| 列名 | 列名 | 加一行 |---|），
               系统会把表格渲染成图片，比一长串项目符号好读；不要输出代码块；
            4. 涉及号池趋势、热力图、时间分布时，必须调用 poolHeatmap 工具，
               并把它返回的 Markdown 表格原样放进回答 ——
               那张表的每个时间桶都是一格，粒度到小时；不要自己用「热度」之类的
               单列去概括，也不要自己拼 emoji；
            5. 涉及指标时说明时间窗（例如「近 24 小时」）；
            6. 工具查不到就直说查不到，不要猜测。
            """;

    private static final String MONITOR_USER_PROMPT = """
            你是「上游账号监控」系统的助手，通过 QQ 回答用户的问题。
            当前对话者是本平台的普通用户（不是管理员）。
            要求：
            1. 平台级数据（账号列表、余额、号池、密钥、变更记录等）只有管理员能查询；
               对方问到这类内容时，直接说明「该数据仅管理员可查」，不要猜测、不要编造；
            2. 你可以介绍平台的一般用法、解释概念，以及回答与用户本人相关的问题；
            3. 绝不编造任何数字；
            4. 用简体中文，直接给结论；不要输出代码块。
            """;

    private static final String CHAT_PROMPT = """
            你是一个通过 QQ 聊天的聊天机器人。
            硬性规则，任何情况下都不能违反：
            1. 你不知道、也不讨论任何与「平台」「上游」「账号」「余额」「额度」「号池」
               「密钥」「监控」「采集」有关的内容；
            2. 不要承认或暗示存在这类系统，不要解释你为什么不说，也不要提「管理员」「权限」；
            3. 遇到这类提问，就像普通朋友聊天一样自然地把话题岔开；
            4. 用简体中文，口语化、简短，不要输出 Markdown 表格或代码块。
            """;

    private static final String ADMIN_HELP = """
            可用命令：
            - /平台            上游平台概览
            - /号池 [时间窗]   号池渠道的缓存率、首 Token、每秒 Token 与趋势矩阵
            - /采集            号池直连库增量采集状态
            - /账号 <关键字>   按名称查账号余额与状态
            - /变更 [条数]     最近的账号/密钥变更
            - /我的信息        查看当前绑定的邮箱
            - /绑定 <邮箱>     绑定邮箱
            - /解绑            解除绑定
            也可以直接说人话，例如「近 7 天哪个号池账号缓存率最低？」
            内容较长时会自动渲染成表格图片。""";

    private static final String USER_HELP = """
            可用命令：
            - /我的信息        查看当前绑定的邮箱
            - /绑定 <邮箱>     绑定邮箱
            - /解绑            解除绑定
            平台级数据（账号、余额、号池、密钥）仅管理员可查。""";

    private static final String GUEST_HELP = """
            我能做的事：
            - /绑定 <邮箱>     绑定你的邮箱
            - /解绑            解除绑定
            - /我的信息        查看当前绑定
            也可以直接和我聊天。""";

    /** 非平台用户看到「未知命令」时的统一回复，不暴露平台功能的存在。 */
    private static final String UNKNOWN_COMMAND = "这个命令我用不了，发送 /help 看看能做什么。";

    private final BotSettingsService settingsService;
    private final OneBotClient client;
    private final MonitorChatTools tools;
    private final BotIdentityResolver identityResolver;
    private final QqUserBindingService bindings;
    private final BotReportRenderer reportRenderer;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "qq-bot");
        thread.setDaemon(true);
        return thread;
    });

    /** 每种人设一个客户端；记忆窗口变了会重建。 */
    private final Map<Persona, CachedClient> clients = new ConcurrentHashMap<>();
    private volatile boolean chatClientUnavailable;

    public BotMessageService(BotSettingsService settingsService,
                             OneBotClient client,
                             MonitorChatTools tools,
                             BotIdentityResolver identityResolver,
                             QqUserBindingService bindings,
                             BotReportRenderer reportRenderer,
                             ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.settingsService = settingsService;
        this.client = client;
        this.tools = tools;
        this.identityResolver = identityResolver;
        this.bindings = bindings;
        this.reportRenderer = reportRenderer;
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * 机器人人设。
     *
     * <p>提示词在方法里取而不是构造器参数：枚举常量先于类的静态字段初始化，
     * 直接引用 {@code MONITOR_ADMIN_PROMPT} 会拿到 null。</p>
     */
    private enum Persona {
        /** 平台管理员：监控助手 + 全部只读查询工具。 */
        MONITOR_ADMIN,
        /** 平台普通用户：监控助手，但不挂任何平台级工具。 */
        MONITOR_USER,
        /** 非平台用户：纯聊天，绝不涉及平台。 */
        CHAT;

        String systemPrompt() {
            return switch (this) {
                case MONITOR_ADMIN -> MONITOR_ADMIN_PROMPT;
                case MONITOR_USER -> MONITOR_USER_PROMPT;
                case CHAT -> CHAT_PROMPT;
            };
        }

        /** 只有管理员才挂载平台查询工具。 */
        boolean usesTools() {
            return this == MONITOR_ADMIN;
        }
    }

    private record CachedClient(ChatClient client, int window) {
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
            BotIdentity identity = identityResolver.resolve(event.userId());
            BotReport report;
            try {
                report = answer(event, text, settings, identity);
            } catch (Exception ex) {
                log.warn("处理 QQ 消息失败: user={}, platformUser={}, reason={}",
                        event.userId(), identity.platformUser(), ex.getMessage());
                // 异常细节只回给平台用户，避免向陌生人泄露内部实现
                report = BotReport.fromMarkdown(identity.platformUser()
                        ? "处理这条消息时出错了：" + ex.getMessage()
                        : "处理这条消息时出错了，请稍后再试。");
            }
            send(event, group, report, settings.maxReplyLength());
        });
    }

    private boolean allowed(OneBotEvent event, boolean group, BotSettings settings) {
        return group ? settings.isGroupAllowed(event.groupId())
                : settings.isUserAllowed(event.userId());
    }

    private BotReport answer(OneBotEvent event, String text, BotSettings settings, BotIdentity identity) {
        String prefix = settings.commandPrefix();
        if (prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return command(text.substring(prefix.length()).trim(), identity, event);
        }
        return askModel(event, text, settings, identity);
    }

    /** 确定性命令：没配大模型也能用。 */
    private BotReport command(String commandLine, BotIdentity identity, OneBotEvent event) {
        String[] parts = commandLine.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (name) {
            case "help", "帮助", "?" -> text(help(identity));
            case "bind", "绑定" -> text(bind(arg, event));
            case "unbind", "解绑" -> text(unbind(event));
            case "whoami", "我的信息", "me" -> text(whoami(identity));

            // 平台级命令：非平台用户一律按「未知命令」处理，不暴露功能存在
            case "platform", "平台", "overview" -> platformOnly(identity, tools::platformReport);
            case "pool", "号池" -> platformOnly(identity, () -> tools.poolReport(arg));
            case "ingest", "采集" -> platformOnly(identity, tools::ingestReport);
            case "account", "账号" -> platformOnly(identity, () -> tools.accountReport(arg));
            case "changes", "变更" -> platformOnly(identity, () -> tools.changesReport(parseInt(arg, 5)));

            default -> text(identity.platformUser()
                    ? "未知命令：" + name + "\n" + help(identity)
                    : UNKNOWN_COMMAND);
        };
    }

    /**
     * 平台级命令的统一闸门。
     *
     * <p>非平台用户必须看起来「压根没有这个功能」：返回和未知命令完全一样的提示，
     * 而不是「权限不足」，否则等于告诉对方平台和权限体系的存在。</p>
     */
    private BotReport platformOnly(BotIdentity identity, Supplier<BotReport> action) {
        if (!identity.platformUser()) {
            return text(UNKNOWN_COMMAND);
        }
        if (!identity.admin()) {
            return text("平台级数据仅管理员可查。");
        }
        return action.get();
    }

    /** 纯文本包成报表：短内容仍按纯文本发出，长内容走段落渲染。 */
    private static BotReport text(String value) {
        return BotReport.fromMarkdown(value);
    }

    private String help(BotIdentity identity) {
        if (!identity.platformUser()) {
            return GUEST_HELP;
        }
        return identity.admin() ? ADMIN_HELP : USER_HELP;
    }

    /**
     * 绑定邮箱。
     *
     * <p>无论邮箱是否命中平台用户列表，回复都一样：否则任何人都能靠反复绑定邮箱，
     * 从回复差异里枚举出哪些邮箱是平台用户。</p>
     */
    private String bind(String email, OneBotEvent event) {
        if (email.isBlank()) {
            return "用法：/绑定 <邮箱>";
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return "邮箱格式不正确。用法：/绑定 <邮箱>";
        }
        return bindings.bind(event.userId(), email) ? "已记录你的邮箱。" : "绑定失败，请稍后再试。";
    }

    private String unbind(OneBotEvent event) {
        bindings.unbind(event.userId());
        return "已解除绑定。";
    }

    /**
     * 查看绑定信息。
     *
     * <p>只回邮箱，不回角色 / 是否平台用户 —— 与绑定同理，避免被用来枚举平台用户。</p>
     */
    private String whoami(BotIdentity identity) {
        if (!identity.hasEmail()) {
            return "当前没有绑定邮箱。发送 /绑定 <邮箱> 完成绑定。";
        }
        return "当前绑定邮箱：" + identity.email();
    }

    private BotReport askModel(OneBotEvent event, String text, BotSettings settings, BotIdentity identity) {
        Persona persona = personaOf(identity);
        ChatClient chatClient = chatClient(persona, settings);
        if (chatClient == null) {
            return text("自然语言问答未启用（需要在配置里打开大模型）。当前可用命令：\n" + help(identity));
        }
        String answer = chatClient.prompt()
                .user(text)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId(event, persona)))
                .call()
                .content();
        // 大模型写 Markdown 表格，这里解析成结构化报表，长回答就能渲染成表格图片
        return BotReport.fromMarkdown(answer);
    }

    private Persona personaOf(BotIdentity identity) {
        if (!identity.platformUser()) {
            return Persona.CHAT;
        }
        return identity.admin() ? Persona.MONITOR_ADMIN : Persona.MONITOR_USER;
    }

    /**
     * 构建（并按人设 + 记忆窗口缓存）ChatClient。
     *
     * <p>{@code ChatClient.Builder} 在 Spring AI 里是 prototype Bean，每次取都是新实例，
     * 因此不同人设之间不会互相带上对方的默认工具 —— 这点对「陌生人拿不到平台工具」是硬要求。</p>
     */
    private ChatClient chatClient(Persona persona, BotSettings settings) {
        if (chatClientUnavailable) {
            return null;
        }
        int window = Math.max(2, settings.memoryWindow());
        CachedClient cached = clients.get(persona);
        if (cached != null && cached.window() == window) {
            return cached.client();
        }
        synchronized (this) {
            cached = clients.get(persona);
            if (cached != null && cached.window() == window) {
                return cached.client();
            }
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                chatClientUnavailable = true;
                log.info("未配置大模型，QQ 机器人只支持命令（/help 查看）");
                return null;
            }
            ChatClient.Builder configured = builder
                    .defaultSystem(persona.systemPrompt())
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
                            MessageWindowChatMemory.builder().maxMessages(window).build())
                            .build());
            if (persona.usesTools()) {
                configured = configured.defaultTools(tools);
            }
            ChatClient built = configured.build();
            clients.put(persona, new CachedClient(built, window));
            log.info("QQ 机器人人设已就绪: persona={}, 记忆窗口={} 条, 平台工具={}",
                    persona, window, persona.usesTools());
            return built;
        }
    }

    /** 会话记忆按人设隔离：身份变化后不会串用上一段人设的上下文。 */
    private String conversationId(OneBotEvent event, Persona persona) {
        String scope = "group".equals(event.messageType())
                ? "qq-group-" + event.groupId()
                : "qq-user-" + event.userId();
        return persona.name().toLowerCase(Locale.ROOT) + "-" + scope;
    }

    /**
     * 发送回复。
     *
     * <p>超过 {@code maxReplyLength} 的内容会被截断，此时改为渲染成表格 / 热力图图片 ——
     * 渠道状态、账户余额这类内容本质是表格，图片才能完整呈现。渲染或发送图片失败时
     * 再退回截断文本，保证「至少有东西发出去」。</p>
     */
    private void send(OneBotEvent event, boolean group, BotReport report, int maxReplyLength) {
        String plain = report.toPlainText();
        if (plain.isBlank()) {
            plain = "（没有查到内容）";
            report = BotReport.fromMarkdown(plain);
        }
        String truncated = truncate(plain, maxReplyLength);
        if (truncated.equals(plain)) {
            sendText(event, group, plain);
            return;
        }

        byte[] png = reportRenderer.render(report);
        if (png != null && sendImage(event, group, png)) {
            log.info("回复过长（{} 字），已转为图片发送: user={}, bytes={}",
                    plain.length(), event.userId(), png.length);
            return;
        }
        sendText(event, group, truncated);
    }

    private void sendText(OneBotEvent event, boolean group, String text) {
        if (group) {
            client.sendGroupMessage(event.groupId(), text);
        } else {
            client.sendPrivateMessage(event.userId(), text);
        }
    }

    private boolean sendImage(OneBotEvent event, boolean group, byte[] png) {
        return group
                ? client.sendGroupImage(event.groupId(), png)
                : client.sendPrivateImage(event.userId(), png);
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

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}