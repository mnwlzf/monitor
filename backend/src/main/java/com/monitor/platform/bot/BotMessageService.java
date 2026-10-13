package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.user.BotUserTools;
import com.monitor.platform.bot.channel.BotChannelStatusTools;
import com.monitor.platform.bot.channel.BotChannelStatusService;
import com.monitor.platform.bot.image.GeneratedImages;
import com.monitor.platform.bot.image.ImageGenService;
import com.monitor.platform.bot.image.ImageGenTools;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.report.BotReportRenderer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <p>确定性命令（/平台、/号池、/绑定……）的路由与权限见 {@link BotCommandRouter}，
 * 这里只负责编排：收消息 → 判断走命令还是大模型 → 把结果发出去。</p>
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

    /**
     * 生图开始时的开场提示。
     *
     * <p>生图一次要 20 秒以上、加上发图接近 40 秒，没有这句用户会以为机器人没反应。</p>
     */
    private static final String IMAGE_START_NOTICE = "🎨 收到，正在画，大概 30 秒～1 分钟，画好就发给你～";

    private final BotSettingsService settingsService;
    private final OneBotClient client;
    private final BotMessageParser parser;
    private final MonitorChatTools tools;
    private final BotChannelStatusService channelStatusService;
    private final ImageGenService imageGenService;
    private final BotRuntimeStatus runtimeStatus;
    private final BotUserService botUserService;
    private final BotMessageArchiveService archive;
    private final BotImageFetcher imageFetcher;
    private final BotVisionProperties visionProperties;
    private final BotIdentityResolver identityResolver;
    private final BotReportRenderer reportRenderer;
    private final BotChatClientFactory chatClients;
    private final BotCommandRouter commandRouter;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "qq-bot");
        thread.setDaemon(true);
        return thread;
    });

    public BotMessageService(BotSettingsService settingsService,
                             OneBotClient client,
                             BotMessageParser parser,
                             MonitorChatTools tools,
                             BotChannelStatusService channelStatusService,
                             ImageGenService imageGenService,
                             BotRuntimeStatus runtimeStatus,
                             BotUserService botUserService,
                             BotMessageArchiveService archive,
                             BotImageFetcher imageFetcher,
                             BotVisionProperties visionProperties,
                             BotIdentityResolver identityResolver,
                             BotReportRenderer reportRenderer,
                             BotChatClientFactory chatClients,
                             BotCommandRouter commandRouter) {
        this.settingsService = settingsService;
        this.client = client;
        this.parser = parser;
        this.tools = tools;
        this.channelStatusService = channelStatusService;
        this.imageGenService = imageGenService;
        this.runtimeStatus = runtimeStatus;
        this.botUserService = botUserService;
        this.archive = archive;
        this.imageFetcher = imageFetcher;
        this.visionProperties = visionProperties;
        this.identityResolver = identityResolver;
        this.reportRenderer = reportRenderer;
        this.chatClients = chatClients;
        this.commandRouter = commandRouter;
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
        if (!allowed(event, channel, settings)) {
            // 刻意用 info：这条以前是 debug，导致「机器人为什么不回我」在默认日志级别下完全无痕
            log.info("忽略非白名单会话: channel={}, 来源群={}, user={}",
                    channel, event.groupId(), event.userId());
            runtimeStatus.dropped(channel, event.groupId(), event.userId(), "不在白名单（群号 / QQ）");
            return;
        }
        if (group && settings.requireMention() && !parser.mentioned(event)) {
            // 刻意不计入「未回复」：这条消息本来就不需要机器人回，
            // 计进去会让统计被正常群聊刷满，反而看不出真正被拦下的消息
            return;
        }
        String text = parser.extractText(event);
        // 引用的那条消息：QQ 的 reply 段通常只带 id，要取回原文模型才看得见；
        // 被引用的消息本身可能就是图片（「引用这张图，总结一下」），所以连图片一起取回来
        BotMessageParser.QuotedMessage quoted = parser.quotedMessage(event);
        // 图片段单独抽出来：之前只认文本段，导致「只发图片」的消息被当成空消息直接丢掉
        List<BotImageFetcher.ImageRef> ownImageRefs = visionProperties.isEnabled()
                ? BotImageFetcher.extract(event.message())
                : List.of();
        List<BotImageFetcher.ImageRef> quotedImageRefs = visionProperties.isEnabled()
                ? quoted.images()
                : List.of();
        if (text.isBlank() && quoted.text().isBlank() && ownImageRefs.isEmpty() && quotedImageRefs.isEmpty()) {
            // 既没文字、没引用也没图片（表情、语音等），目前不支持
            runtimeStatus.dropped(channel, event.groupId(), event.userId(), "空消息（表情/语音等暂不支持）");
            return;
        }
        runtimeStatus.inbound();
        // 「机器人状态」页要能看到正在和谁对话、说的什么；只引用一条消息时把引用内容当内容
        String turnId = runtimeStatus.beginTurn(channel, event.groupId(), event.userId(),
                !text.isBlank() ? text : (quoted.text().isBlank() ? "" : "（引用了：" + quoted.text() + "）"));
        executor.submit(() -> {
            try {
                BotIdentity identity = identityResolver.resolve(event.userId());
                Long contextGroup = channel.hasGroupContext() ? event.groupId() : null;
                boolean platformFeature =
                        BotAccessPolicy.platformFeatureAllowed(identity, channel, contextGroup, settings);
                if (channel == BotChannel.TEMP_SESSION) {
                    // 临时会话探针：确认 OneBot 实现是否带了发起群号（拿不到就按「不在指定群」处理）
                    log.info("收到群临时会话: user={}, 发起群={}, 触发平台功能={}",
                            event.userId(), event.groupId(), platformFeature);
                }
                // 下载图片放在后台线程里做，不占用回调线程；自己发的与被引用的分开下，存档时能说清来源
                List<BotImageFetcher.FetchedImage> ownImages = imageFetcher.fetch(ownImageRefs);
                List<BotImageFetcher.FetchedImage> quotedImages = imageFetcher.fetch(quotedImageRefs);
                List<BotImageFetcher.FetchedImage> images = new ArrayList<>(ownImages);
                images.addAll(quotedImages);

                // 先存档用户消息：即使后面回答失败，也能查证「他说了什么、发了什么图」
                String correlationId = archive.recordInbound(event,
                        describeIncoming(text, ownImageRefs.size(), ownImages.size(),
                                quotedImageRefs.size(), quotedImages.size()),
                        kindOf(text, settings, !ownImageRefs.isEmpty() || !quotedImageRefs.isEmpty()), identity);

                GeneratedImages generated = new GeneratedImages();
                BotReport report;
                try {
                    report = answer(event, text, quoted, !quotedImages.isEmpty(), settings, identity, platformFeature, channel, images, generated);
                } catch (Exception ex) {
                    log.warn("处理 QQ 消息失败: user={}, platformUser={}, reason={}",
                            event.userId(), identity.platformUser(), ex.getMessage());
                    runtimeStatus.failed(event.userId(), ex.getMessage());
                    // 异常细节只回给有平台功能的会话，避免向陌生人泄露内部实现
                    report = BotReport.fromMarkdown(platformFeature
                            ? "处理这条消息时出错了：" + ex.getMessage()
                            : "处理这条消息时出错了，请稍后再试。");
                }

                SendOutcome outcome;
                try {
                    outcome = send(event, group, report, settings.maxReplyLength(), generated);
                } catch (RuntimeException sendError) {
                    // 发送失败也要把这一轮从「正在处理」里摘掉，否则会永远挂在那儿
                    runtimeStatus.finishTurn(turnId, "发送失败：" + sendError.getMessage());
                    throw sendError;
                }
                runtimeStatus.replied();
                runtimeStatus.finishTurn(turnId, outcome.content());
                // 再存档机器人回复：人设也记下来，便于日后解释「当时为什么这么答」
                archive.recordOutbound(event, outcome.content(), outcome.kind(), identity,
                        personaOf(identity, platformFeature).name(), correlationId);
            } catch (Exception uncaught) {
                // submit() 返回的 Future 没人接收，任务里未捕获的异常会被静默吞掉：
                // 没有日志、没有回复、连存档都没有 —— 线上表现就是「调用失败但用户什么都没收到」。
                log.error("处理消息时出现未捕获异常: user={}, channel={}", event.userId(), channel, uncaught);
                runtimeStatus.failed(event.userId(), uncaught.getMessage());
                runtimeStatus.finishTurn(turnId, "处理失败：" + uncaught.getMessage());
                sendText(event, group, "处理这条消息时出错了，请稍后再试。");
            }
        });
    }

    /** 大模型是否可用（供「机器人状态」页展示）。 */
    public boolean llmAvailable() {
        return chatClients.available();
    }

    /**
     * 是否允许这条消息触发机器人。
     *
     * <ul>
     *     <li>{@code GROUP} / {@code TEMP_SESSION}：只看<strong>群</strong>是否在「允许的群号」里。
     *         临时会话的<strong>唯一限制就是发起它的那个群</strong>，不看发送者 QQ ——
     *         它本质是「群成员跟机器人说话」，权限继承自群，和群里 @机器人 保持一致。
     *         取不到群号时按不在白名单处理（fail-closed）；</li>
     *     <li>{@code PRIVATE}：看发送者是否在「允许的私聊 QQ」里 ——
     *         这一条是给<strong>好友私聊</strong>用的，用来拦住陌生人加好友后直接私聊。</li>
     * </ul>
     *
     * <p>白名单为空表示<strong>不限制</strong>（见 {@link BotSettings#matches}）。</p>
     */
    private boolean allowed(OneBotEvent event, BotChannel channel, BotSettings settings) {
        return switch (channel) {
            case GROUP, TEMP_SESSION -> settings.isGroupAllowed(event.groupId());
            case PRIVATE -> settings.isUserAllowed(event.userId());
        };
    }

    private BotReport answer(OneBotEvent event, String text, BotMessageParser.QuotedMessage quoted,
                             boolean quotedHasImages, BotSettings settings, BotIdentity identity,
                             boolean platformFeature, BotChannel channel,
                             List<BotImageFetcher.FetchedImage> images, GeneratedImages generated) {
        String prefix = settings.commandPrefix();
        // 只发图片时 text 为空，不能当命令处理，直接交给模型
        if (!text.isBlank() && prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return commandRouter.route(text.substring(prefix.length()).trim(), identity, platformFeature,
                    event, generated, () -> notifyImageStart(event, generated));
        }
        return askModel(event, text, quoted, quotedHasImages, settings, identity, platformFeature, channel, images, generated);
    }

    /** 纯文本包成报表：短内容仍按纯文本发出，长内容走段落渲染。 */
    private static BotReport text(String value) {
        return BotReport.fromMarkdown(value);
    }

    private BotReport askModel(OneBotEvent event, String text, BotMessageParser.QuotedMessage quoted,
                               boolean quotedHasImages, BotSettings settings, BotIdentity identity,
                               boolean platformFeature, BotChannel channel,
                               List<BotImageFetcher.FetchedImage> images, GeneratedImages generated) {
        BotChatClientFactory.Persona persona = personaOf(identity, platformFeature);
        ChatClient chatClient = chatClients.get(persona, settings);
        if (chatClient == null) {
            String message = images.isEmpty()
                    ? "自然语言问答未启用（需要在配置里打开大模型）。当前可用命令：\n" + commandRouter.help(identity, platformFeature)
                    : "看图片需要开启大模型（SPRING_AI_MODEL_CHAT）。当前可用命令：\n" + commandRouter.help(identity, platformFeature);
            return text(message);
        }
        String promptText = parser.buildPrompt(text, quoted.text(), !images.isEmpty(), quotedHasImages);
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
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
                });
        // 用户端工具按请求绑定发送者邮箱：大模型只能查到发送者本人的数据
        if (platformFeature && identity.hasEmail()) {
            request = request.tools(new BotUserTools(botUserService, identity.email()));
        }
        // 渠道状态对「管理员 + 平台用户」都开放，按请求绑定可见范围，普通用户只看自己的分组
        if (platformFeature) {
            request = request.tools(new BotChannelStatusTools(
                    channelStatusService, identity.admin(), commandRouter.viewerUserId(identity)));
        }
        // 生图：所有人都能用，图片归属到本条消息（按请求构造，避免串到别人的回复里）
        request = request.tools(new ImageGenTools(imageGenService, generated,
                    () -> notifyImageStart(event, generated)));
        String answer = request
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

    private BotChatClientFactory.Persona personaOf(BotIdentity identity, boolean platformFeature) {
        if (!platformFeature) {
            return BotChatClientFactory.Persona.CHAT;
        }
        return identity.admin() ? BotChatClientFactory.Persona.MONITOR_ADMIN : BotChatClientFactory.Persona.MONITOR_USER;
    }

    /**
     * 会话记忆按「人设 + 通道 + <strong>说话的人</strong>」隔离。
     *
     * <p><strong>群聊也必须带上 userId</strong>：Spring AI 的 {@code MessageWindowChatMemory}
     * 存的是一串没有署名的 user / assistant 消息，模型分不清哪句是谁说的。如果整个群共用一段记忆，
     * 甲说过的话就会出现在乙的上下文里，看起来就像「把别人的聊天内容加到了另外的人身上」。</p>
     *
     * <p>临时会话与好友私聊都是 private 通道，只按 userId 分组还会把「指定群里问过的内容」
     * 漏到好友私聊里，所以临时会话单独一个作用域。</p>
     *
     * <p>代价：群里不再有跨用户的共享上下文（「他刚才说的那个」这类指代会失效）。
     * 要恢复共享又不串人，得让每一轮都带上说话人标签，那是另一套记忆实现。</p>
     */
    private String conversationId(OneBotEvent event, BotChatClientFactory.Persona persona, BotChannel channel) {
        return persona.name().toLowerCase(Locale.ROOT) + "-"
                + memoryScope(channel, event.groupId(), event.userId());
    }

    /**
     * 会话记忆的作用域（不含人设）。
     *
     * <p>抽成包级静态方法是为了能在单测里直接验证隔离性 —— 这是踩过一次的坑：
     * 群聊漏掉 userId 会让不同的人共用一段记忆。</p>
     */
    static String memoryScope(BotChannel channel, Long groupId, long userId) {
        return switch (channel) {
            case GROUP -> "qq-group-" + groupId + "-" + userId;
            case TEMP_SESSION -> "qq-temp-" + groupId + "-" + userId;
            case PRIVATE -> "qq-user-" + userId;
        };
    }

    /**
     * 发送回复。
     *
     * <p>超过 {@code maxReplyLength} 的内容会被截断，此时改为渲染成表格 / 热力图图片 ——
     * 渠道状态、账户余额这类内容本质是表格，图片才能完整呈现。渲染或发送图片失败时
     * 再退回截断文本，保证「至少有东西发出去」。</p>
     */
    private SendOutcome send(OneBotEvent event, boolean group, BotReport report, int maxReplyLength,
                            GeneratedImages generated) {
        List<byte[]> generatedImages = generated.snapshot();
        // 先生成的图：这是用户明确要的东西，发失败要让他知道，而不是只回一句文字
        for (byte[] image : generatedImages) {
            if (sendImage(event, group, image)) {
                log.info("已发送生成的图片: user={}, bytes={}", event.userId(), image.length);
            } else {
                sendText(event, group, "图片发送失败了，请稍后再试。");
            }
        }
        String plain = report.toPlainText();
        // 兜底：本轮没出图、工具又留了话（例如生图被限频），就必须让用户看到原因。
        // 工具的失败原因是作为「工具返回值」回到模型手里的，模型有可能吞掉甚至答成空白。
        String notice = generated.notice();
        boolean noticeApplied = false;
        if (generatedImages.isEmpty() && notice != null && !notice.isBlank() && !plain.contains(notice)) {
            plain = plain.isBlank() ? notice : notice + "\n" + plain;
            noticeApplied = true;
        }
        if (plain.isBlank()) {
            plain = "（没有查到内容）";
            noticeApplied = true;
        }
        if (noticeApplied) {
            report = BotReport.fromMarkdown(plain);
        }
        String truncated = parser.truncate(plain, maxReplyLength);
        if (truncated.equals(plain)) {
            sendText(event, group, plain);
            return new SendOutcome(archiveContent(plain, generatedImages), archiveKind(generatedImages));
        }

        byte[] png = reportRenderer.render(report);
        if (png != null && sendImage(event, group, png)) {
            log.info("回复过长（{} 字），已转为图片发送: user={}, bytes={}",
                    plain.length(), event.userId(), png.length);
            // 存档存文本内容（就是图片上的字），便于日后检索；kind 标成 IMAGE
            return new SendOutcome(archiveContent(plain, generatedImages), BotMessageArchiveService.KIND_IMAGE);
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
    private static String describeIncoming(String text, int total, int fetched,
                                           int quotedTotal, int quotedFetched) {
        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append("[图片 ×").append(total);
            if (fetched < total) {
                sb.append("，成功读取 ").append(fetched).append(" 张");
            }
            sb.append("]");
        }
        if (quotedTotal > 0) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append("[引用图片 ×").append(quotedTotal);
            if (quotedFetched < quotedTotal) {
                sb.append("，成功读取 ").append(quotedFetched).append(" 张");
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

    /**
     * 发一句「正在画，稍等」。
     *
     * <p>一轮对话只发一次（模型可能连着调几次工具）。刻意由生图服务在
     * <strong>通过限频之后</strong>回调触发，避免先「正在画…」再「太频繁了」。</p>
     */
    private void notifyImageStart(OneBotEvent event, GeneratedImages generated) {
        if (!generated.markStartNotified()) {
            return;
        }
        boolean group = "group".equals(event.messageType());
        sendText(event, group, IMAGE_START_NOTICE);
        log.info("已发送生图开场提示: user={}, group={}", event.userId(), event.groupId());
    }

    /** 存档内容带上「本次还发了生成图」的标记，便于事后检索。 */
    private static String archiveContent(String text, List<byte[]> generatedImages) {
        return generatedImages.isEmpty() ? text : "[生成图 ×" + generatedImages.size() + "] " + text;
    }

    private static String archiveKind(List<byte[]> generatedImages) {
        return generatedImages.isEmpty()
                ? BotMessageArchiveService.KIND_TEXT
                : BotMessageArchiveService.KIND_IMAGE;
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

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
