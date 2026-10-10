package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.weather.WeatherTools;
import com.monitor.platform.bot.report.BotReportRenderer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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
 * 平台功能要<strong>双重命中</strong>才触发：邮箱匹配出身份，且消息落在「指定群」上下文里
 * （群聊取本群，群临时会话取发起群）。判定见 {@link BotAccessPolicy}：
 * <ul>
 *     <li><b>管理员</b>：好友私聊 / 群临时会话 / 指定群 → 监控助手 + 全部只读查询工具；</li>
 *     <li><b>平台普通用户</b>：仅指定群及其临时会话 → 监控助手，但<strong>不挂平台级工具</strong>，
 *         问到平台数据只回「仅管理员可查」；好友私聊只给正常功能；</li>
 *     <li><b>其他人</b>（陌生人 / 非指定群）：纯聊天机器人，<strong>绝不能出现任何平台相关信息</strong>。</li>
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

    /**
     * 热力图标记：大模型用它表示「这里要放真正的热力图」。
     *
     * <p>不直接把矩阵交给大模型，是因为它抄不准 20 行 × 24 列以上的 emoji 矩阵，
     * 会把图案「重写」一遍，和页面完全对不上。改为由后端按标记确定性渲染。</p>
     */
    private static final Pattern HEATMAP_MARKER = Pattern.compile("\\[\\[HEATMAP:([A-Za-z0-9]+)]]");

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
               并把它返回的 [[HEATMAP:...]] 标记原样放进回答；
               系统会在标记处渲染出真正的热力图。不要自己画表格、色块或 emoji ——
               矩阵太大，你自己重写会和页面数据对不上；
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
            - /天气 <城市>     实时天气与预报（所有人都能用）
            也可以直接说人话，例如「近 7 天哪个号池账号缓存率最低？」
            内容较长时会自动渲染成表格图片。""";

    private static final String USER_HELP = """
            可用命令：
            - /我的信息        查看当前绑定的邮箱
            - /绑定 <邮箱>     绑定邮箱
            - /解绑            解除绑定
            - /天气 <城市>     实时天气与预报（所有人都能用）
            平台级数据（账号、余额、号池、密钥）仅管理员可查。""";

    private static final String GUEST_HELP = """
            我能做的事：
            - /绑定 <邮箱>     绑定你的邮箱
            - /解绑            解除绑定
            - /我的信息        查看当前绑定
            - /天气 <城市>     实时天气与预报
            也可以直接和我聊天。""";

    /** 非平台用户看到「未知命令」时的统一回复，不暴露平台功能的存在。 */
    private static final String UNKNOWN_COMMAND = "这个命令我用不了，发送 /help 看看能做什么。";

    /** 用户只发图片、没带文字时的提示词。 */
    private static final String IMAGE_ONLY_PROMPT = "这是我发来的图片，请描述并回应。";

    private final BotSettingsService settingsService;
    private final OneBotClient client;
    private final MonitorChatTools tools;
    private final WeatherTools weatherTools;
    private final BotMessageArchiveService archive;
    private final BotImageFetcher imageFetcher;
    private final BotVisionProperties visionProperties;
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
                             WeatherTools weatherTools,
                             BotMessageArchiveService archive,
                             BotImageFetcher imageFetcher,
                             BotVisionProperties visionProperties,
                             BotIdentityResolver identityResolver,
                             QqUserBindingService bindings,
                             BotReportRenderer reportRenderer,
                             ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.settingsService = settingsService;
        this.client = client;
        this.tools = tools;
        this.weatherTools = weatherTools;
        this.archive = archive;
        this.imageFetcher = imageFetcher;
        this.visionProperties = visionProperties;
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
        BotChannel channel = BotChannel.of(event);
        if (channel == null) {
            return;
        }
        boolean group = channel == BotChannel.GROUP;
        if (!allowed(event, group, settings)) {
            log.debug("忽略非白名单会话: group={}, user={}", event.groupId(), event.userId());
            return;
        }
        if (group && settings.requireMention() && !mentioned(event)) {
            return;
        }
        String text = extractText(event);
        // 图片段单独抽出来：之前只认文本段，导致「只发图片」的消息被当成空消息直接丢掉
        List<BotImageFetcher.ImageRef> imageRefs = visionProperties.isEnabled()
                ? BotImageFetcher.extract(event.message())
                : List.of();
        if (text.isBlank() && imageRefs.isEmpty()) {
            // 既没文字也没图片（表情、语音等），目前不支持
            return;
        }
        executor.submit(() -> {
            BotIdentity identity = identityResolver.resolve(event.userId());
            Long contextGroup = channel.hasGroupContext() ? event.groupId() : null;
            boolean platformFeature =
                    BotAccessPolicy.platformFeatureAllowed(identity, channel, contextGroup, settings);
            if (channel == BotChannel.TEMP_SESSION) {
                // 临时会话探针：确认 OneBot 实现是否带了发起群号（拿不到就按「不在指定群」处理）
                log.info("收到群临时会话: user={}, 发起群={}, 触发平台功能={}",
                        event.userId(), event.groupId(), platformFeature);
            }
            // 下载图片放在后台线程里做，不占用回调线程
            List<BotImageFetcher.FetchedImage> images = imageFetcher.fetch(imageRefs);

            // 先存档用户消息：即使后面回答失败，也能查证「他说了什么、发了什么图」
            String correlationId = archive.recordInbound(event,
                    describeIncoming(text, imageRefs.size(), images.size()),
                    kindOf(text, settings, !imageRefs.isEmpty()), identity);

            BotReport report;
            try {
                report = answer(event, text, settings, identity, platformFeature, channel, images);
            } catch (Exception ex) {
                log.warn("处理 QQ 消息失败: user={}, platformUser={}, reason={}",
                        event.userId(), identity.platformUser(), ex.getMessage());
                // 异常细节只回给有平台功能的会话，避免向陌生人泄露内部实现
                report = BotReport.fromMarkdown(platformFeature
                        ? "处理这条消息时出错了：" + ex.getMessage()
                        : "处理这条消息时出错了，请稍后再试。");
            }

            SendOutcome outcome = send(event, group, report, settings.maxReplyLength());
            // 再存档机器人回复：人设也记下来，便于日后解释「当时为什么这么答」
            archive.recordOutbound(event, outcome.content(), outcome.kind(), identity,
                    personaOf(identity, platformFeature).name(), correlationId);
        });
    }

    private boolean allowed(OneBotEvent event, boolean group, BotSettings settings) {
        return group ? settings.isGroupAllowed(event.groupId())
                : settings.isUserAllowed(event.userId());
    }

    private BotReport answer(OneBotEvent event, String text, BotSettings settings, BotIdentity identity,
                             boolean platformFeature, BotChannel channel,
                             List<BotImageFetcher.FetchedImage> images) {
        String prefix = settings.commandPrefix();
        // 只发图片时 text 为空，不能当命令处理，直接交给模型
        if (!text.isBlank() && prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return command(text.substring(prefix.length()).trim(), identity, platformFeature, event);
        }
        return askModel(event, text, settings, identity, platformFeature, channel, images);
    }

    /** 确定性命令：没配大模型也能用。 */
    private BotReport command(String commandLine, BotIdentity identity, boolean platformFeature,
                              OneBotEvent event) {
        String[] parts = commandLine.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (name) {
            case "help", "帮助", "?" -> text(help(identity, platformFeature));
            case "bind", "绑定" -> text(bind(arg, event));
            case "unbind", "解绑" -> text(unbind(event));
            case "whoami", "我的信息", "me" -> text(whoami(identity));

            // 天气与平台无关，所有人（含陌生人）都能用
            case "weather", "天气" -> weather(arg);

            // 平台级命令：没有平台功能（未命中指定群 / 陌生人）一律按「未知命令」处理，不暴露功能存在
            case "platform", "平台", "overview" -> platformOnly(identity, platformFeature, tools::platformReport);
            case "pool", "号池" -> platformOnly(identity, platformFeature, () -> tools.poolReport(arg));
            case "ingest", "采集" -> platformOnly(identity, platformFeature, tools::ingestReport);
            case "account", "账号" -> platformOnly(identity, platformFeature, () -> tools.accountReport(arg));
            case "changes", "变更" -> platformOnly(identity, platformFeature, () -> tools.changesReport(parseInt(arg, 5)));

            default -> text(platformFeature
                    ? "未知命令：" + name + "\n" + help(identity, platformFeature)
                    : UNKNOWN_COMMAND);
        };
    }

    /**
     * 平台级命令的统一闸门。
     *
     * <p>非平台用户必须看起来「压根没有这个功能」：返回和未知命令完全一样的提示，
     * 而不是「权限不足」，否则等于告诉对方平台和权限体系的存在。</p>
     */
    private BotReport platformOnly(BotIdentity identity, boolean platformFeature, Supplier<BotReport> action) {
        if (!platformFeature) {
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

    private String help(BotIdentity identity, boolean platformFeature) {
        if (!platformFeature) {
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

    /**
     * 天气查询：{@code /天气 <城市> [天数]}。
     *
     * <p>所有人都能用，因此不经过 {@code platformOnly} 闸门，也不会泄露平台信息。</p>
     */
    private BotReport weather(String arg) {
        String[] parts = arg.split("\\s+", 2);
        String city = parts.length > 0 ? parts[0].trim() : "";
        int days = parts.length > 1 ? parseInt(parts[1], -1) : -1;
        if (days < 0) {
            days = weatherTools.defaultForecastDays();
        }
        return weatherTools.weatherReport(city, days);
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

    private BotReport askModel(OneBotEvent event, String text, BotSettings settings, BotIdentity identity,
                               boolean platformFeature, BotChannel channel,
                               List<BotImageFetcher.FetchedImage> images) {
        Persona persona = personaOf(identity, platformFeature);
        ChatClient chatClient = chatClient(persona, settings);
        if (chatClient == null) {
            String message = images.isEmpty()
                    ? "自然语言问答未启用（需要在配置里打开大模型）。当前可用命令：\n" + help(identity, platformFeature)
                    : "看图片需要开启大模型（SPRING_AI_MODEL_CHAT）。当前可用命令：\n" + help(identity, platformFeature);
            return text(message);
        }
        String promptText = text.isBlank() ? IMAGE_ONLY_PROMPT : text;
        String answer = chatClient.prompt()
                .user(spec -> {
                    spec.text(promptText);
                    // 图片以多模态附件交给模型；模型不支持视觉时上游会报错，由外层统一兜底
                    for (BotImageFetcher.FetchedImage image : images) {
                        byte[] bytes = image.bytes();
                        spec.media(new Media(image.mediaType(), new ByteArrayResource(bytes) {
                            @Override
                            public String getFilename() {
                                return "image." + image.mediaType().getSubtype();
                            }
                        }));
                    }
                })
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId(event, persona, channel)))
                .call()
                .content();

        // 大模型写 Markdown 表格，这里解析成结构化报表，长回答就能渲染成表格图片
        BotReport report = BotReport.fromMarkdown(answer);

        // 出现 [[HEATMAP:24h]] 标记时，把标记换成后端确定性渲染的热力图，
        // 避免大模型自己「重写」矩阵导致图案与页面不一致
        Matcher matcher = HEATMAP_MARKER.matcher(answer);
        if (matcher.find()) {
            String range = matcher.group(1);
            String cleaned = answer.replace(matcher.group(), "").strip();
            report = mergeHeatmap(BotReport.fromMarkdown(cleaned), tools.poolReport(range));
        }
        return report;
    }

    /** 把大模型的评论与确定性渲染的热力图拼成一张报表。 */
    static BotReport mergeHeatmap(BotReport comment, BotReport heatmap) {
        List<BotReport.Block> blocks = new ArrayList<>(comment.blocks());
        blocks.addAll(heatmap.blocks());
        String title = comment.title() == null || comment.title().isBlank() ? heatmap.title() : comment.title();
        return new BotReport(title, blocks, heatmap.notes());
    }

    private Persona personaOf(BotIdentity identity, boolean platformFeature) {
        if (!platformFeature) {
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
            // 天气与平台无关，三种人设都能用（含陌生人）
            configured = persona.usesTools()
                    ? configured.defaultTools(tools, weatherTools)
                    : configured.defaultTools(weatherTools);
            ChatClient built = configured.build();
            clients.put(persona, new CachedClient(built, window));
            log.info("QQ 机器人人设已就绪: persona={}, 记忆窗口={} 条, 平台工具={}",
                    persona, window, persona.usesTools());
            return built;
        }
    }

    /**
     * 会话记忆按「人设 + 通道」隔离。
     *
     * <p>临时会话与好友私聊都是 private，若只按 userId 分组会串上下文：
     * 指定群里问过的内容会漏到好友私聊里。这里给临时会话单独一个作用域。</p>
     */
    private String conversationId(OneBotEvent event, Persona persona, BotChannel channel) {
        String scope = switch (channel) {
            case GROUP -> "qq-group-" + event.groupId();
            case TEMP_SESSION -> "qq-temp-" + event.groupId() + "-" + event.userId();
            case PRIVATE -> "qq-user-" + event.userId();
        };
        return persona.name().toLowerCase(Locale.ROOT) + "-" + scope;
    }

    /**
     * 发送回复。
     *
     * <p>超过 {@code maxReplyLength} 的内容会被截断，此时改为渲染成表格 / 热力图图片 ——
     * 渠道状态、账户余额这类内容本质是表格，图片才能完整呈现。渲染或发送图片失败时
     * 再退回截断文本，保证「至少有东西发出去」。</p>
     */
    private SendOutcome send(OneBotEvent event, boolean group, BotReport report, int maxReplyLength) {
        String plain = report.toPlainText();
        if (plain.isBlank()) {
            plain = "（没有查到内容）";
            report = BotReport.fromMarkdown(plain);
        }
        String truncated = truncate(plain, maxReplyLength);
        if (truncated.equals(plain)) {
            sendText(event, group, plain);
            return new SendOutcome(plain, BotMessageArchiveService.KIND_TEXT);
        }

        byte[] png = reportRenderer.render(report);
        if (png != null && sendImage(event, group, png)) {
            log.info("回复过长（{} 字），已转为图片发送: user={}, bytes={}",
                    plain.length(), event.userId(), png.length);
            // 存档存文本内容（就是图片上的字），便于日后检索；kind 标成 IMAGE
            return new SendOutcome(plain, BotMessageArchiveService.KIND_IMAGE);
        }
        sendText(event, group, truncated);
        return new SendOutcome(truncated, BotMessageArchiveService.KIND_TEXT);
    }

    /** 判断这条用户消息的形态，用于存档分类。 */
    private static String kindOf(String text, BotSettings settings, boolean hasImages) {
        String prefix = settings.commandPrefix();
        if (!text.isBlank() && prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return BotMessageArchiveService.KIND_COMMAND;
        }
        return hasImages ? BotMessageArchiveService.KIND_IMAGE : BotMessageArchiveService.KIND_TEXT;
    }

    /**
     * 存档里对用户消息的可读描述。
     *
     * <p>图片本身不入库（体积大且会过期），只记数量与读取情况 ——
     * 配合文字内容足够用于事后查证。</p>
     */
    private static String describeIncoming(String text, int total, int fetched) {
        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append("[图片 ×").append(total);
            if (fetched < total) {
                sb.append("，成功读取 ").append(fetched).append(" 张");
            }
            sb.append("]");
        }
        if (text != null && !text.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /** 实际发出去的内容与形态，用于存档。 */
    private record SendOutcome(String content, String kind) {
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