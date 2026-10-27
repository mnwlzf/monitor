package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.user.BotBalanceService;
import com.monitor.platform.bot.user.BotBalanceTools;
import com.monitor.platform.bot.user.BotKeyUsageService;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.user.BotUserTools;
import com.monitor.platform.bot.channel.BotChannelStatusTools;
import com.monitor.platform.bot.channel.BotChannelStatusService;
import com.monitor.platform.bot.error.BotErrorQueryAuditService;
import com.monitor.platform.bot.error.BotErrorQueryGuard;
import com.monitor.platform.bot.error.BotErrorService;
import com.monitor.platform.bot.error.BotMyErrorTools;
import com.monitor.platform.bot.image.GeneratedImages;
import com.monitor.platform.bot.image.ImageGenService;
import com.monitor.platform.bot.image.ImageGenTools;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.report.BotGroupRedactor;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.voice.BotSpeechService;
import com.monitor.platform.bot.voice.VoiceReplyIntent;
import com.monitor.platform.bot.report.BotReportRenderer;
import com.monitor.platform.status.OpenAiStatusService;
import com.monitor.platform.status.OpenAiStatusTools;
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
import java.util.Map;
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
 * 群聊和群临时会话可以正常触发平台查询，但最终输出会统一脱敏：
 * <strong>账号名称、上游平台名称/地址和邮箱不会直接展示</strong>。判定见
 * {@link BotAccessPolicy}：
 * <ul>
 *     <li><b>管理员</b>：好友私聊可用全部只读查询工具；指定群内可用但输出脱敏；</li>
 *     <li><b>平台普通用户</b>：指定群内可查自己的数据，输出同样脱敏；</li>
 *     <li><b>其他人</b>：纯聊天机器人，<strong>绝不能出现任何平台相关信息</strong>。</li>
 * </ul>
 * <p>OpenAI 官方服务状态是公开信息，单独放行。</p>
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
    private final BotErrorService errorService;
    private final BotErrorQueryGuard errorQueryGuard;
    private final BotErrorQueryAuditService errorQueryAuditService;
    private final ImageGenService imageGenService;
    private final BotRuntimeStatus runtimeStatus;
    private final BotUserService botUserService;
    private final BotBalanceService botBalanceService;
    private final BotKeyUsageService botKeyUsageService;
    private final BotMessageArchiveService archive;
    private final BotImageFetcher imageFetcher;
    private final BotVisionProperties visionProperties;
    private final BotSpeechService speechService;
    private final BotIdentityResolver identityResolver;
    private final BotReportRenderer reportRenderer;
    private final BotChatClientFactory chatClients;
    private final BotCommandRouter commandRouter;
    private final OpenAiStatusService openAiStatusService;
    private final BotGroupRedactor groupRedactor;
    private final BotForbiddenWordService forbiddenWordService;

    static final String FORBIDDEN_WORD_REPLY = "你的消息触发了违禁词，当前无法回答。请修改后重试。";

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
                             BotErrorService errorService,
                             BotErrorQueryGuard errorQueryGuard,
                             BotErrorQueryAuditService errorQueryAuditService,
                             ImageGenService imageGenService,
                             BotRuntimeStatus runtimeStatus,
                             BotUserService botUserService,
                             BotBalanceService botBalanceService,
                             BotKeyUsageService botKeyUsageService,
                             BotMessageArchiveService archive,
                             BotImageFetcher imageFetcher,
                             BotVisionProperties visionProperties,
                             BotSpeechService speechService,
                             BotIdentityResolver identityResolver,
                             BotReportRenderer reportRenderer,
                             BotChatClientFactory chatClients,
                             BotCommandRouter commandRouter,
                             OpenAiStatusService openAiStatusService,
                             BotGroupRedactor groupRedactor,
                             BotForbiddenWordService forbiddenWordService) {
        this.settingsService = settingsService;
        this.client = client;
        this.parser = parser;
        this.tools = tools;
        this.channelStatusService = channelStatusService;
        this.errorService = errorService;
        this.errorQueryGuard = errorQueryGuard;
        this.errorQueryAuditService = errorQueryAuditService;
        this.imageGenService = imageGenService;
        this.runtimeStatus = runtimeStatus;
        this.botUserService = botUserService;
        this.botBalanceService = botBalanceService;
        this.botKeyUsageService = botKeyUsageService;
        this.archive = archive;
        this.imageFetcher = imageFetcher;
        this.visionProperties = visionProperties;
        this.speechService = speechService;
        this.identityResolver = identityResolver;
        this.reportRenderer = reportRenderer;
        this.chatClients = chatClients;
        this.commandRouter = commandRouter;
        this.openAiStatusService = openAiStatusService;
        this.groupRedactor = groupRedactor;
        this.forbiddenWordService = forbiddenWordService;
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
        String text = parser.extractText(event);
        BotSpeechService.VoiceInput voiceInput = BotSpeechService.extract(event.message(), event.rawMessage());
        boolean hasVoice = voiceInput.present();
        // 引用的那条消息：QQ 的 reply 段通常只带 id，要取回原文模型才看得见；
        // 被引用的消息本身可能就是图片（「引用这张图，总结一下」），所以连图片一起取回来
        BotMessageParser.QuotedMessage quoted = parser.quotedMessage(event);

        // 违禁词对普通用户全渠道拦截，不受白名单或群聊 @ 影响；管理员完全豁免。
        boolean rawForbiddenWord = forbiddenWordService.containsForbiddenWord(text)
                || forbiddenWordService.containsForbiddenWord(quoted.text());
        boolean managementMessage = isForbiddenWordManagementMessage(text, settings);
        BotIdentity earlyIdentity = null;
        boolean containsForbiddenWord = false;
        if (rawForbiddenWord || managementMessage) {
            try {
                earlyIdentity = identityResolver.resolve(event.userId());
            } catch (Exception ex) {
                log.warn("违禁词校验时解析身份失败，按普通用户处理: user={}, reason={}",
                        event.userId(), ex.getMessage());
            }
        }
        boolean earlyAdmin = earlyIdentity != null && earlyIdentity.admin();
        containsForbiddenWord = rawForbiddenWord && !earlyAdmin;
        if (containsForbiddenWord) {
            // 独立提前返回：普通用户不进入白名单/@ 判断，也不请求大模型。
            replyForbiddenWord(event, channel, group, text, quoted, earlyIdentity);
            return;
        }
        // 管理员维护违禁词时不受白名单群和 @ 限制，任意群都可以直接呼出。
        boolean adminForbiddenWordCommand = managementMessage && earlyAdmin;
        if (!allowed(event, channel, settings) && !adminForbiddenWordCommand) {
            // 刻意用 info：这条以前是 debug，导致「机器人为什么不回我」在默认日志级别下完全无痕
            log.info("忽略非白名单会话: channel={}, 来源群={}, user={}",
                    channel, event.groupId(), event.userId());
            runtimeStatus.dropped(channel, event.groupId(), event.userId(), "不在白名单（群号 / QQ）");
            return;
        }
        if (group && settings.requireMention() && !parser.mentioned(event) && !adminForbiddenWordCommand) {
            // 刻意不计入「未回复」：这条消息本来就不需要机器人回，
            // 计进去会让统计被正常群聊刷满，反而看不出真正被拦下的消息
            return;
        }
        // 图片段单独抽出来：之前只认文本段，导致「只发图片」的消息被当成空消息直接丢掉
        List<BotImageFetcher.ImageRef> ownImageRefs = visionProperties.isEnabled()
                ? BotImageFetcher.extract(event.message())
                : List.of();
        List<BotImageFetcher.ImageRef> quotedImageRefs = visionProperties.isEnabled()
                ? quoted.images()
                : List.of();
        if (text.isBlank() && quoted.text().isBlank() && ownImageRefs.isEmpty()
                && quotedImageRefs.isEmpty() && !hasVoice) {
            // 既没文字、没引用、没图片也没语音（表情等），暂不支持
            runtimeStatus.dropped(channel, event.groupId(), event.userId(), "空消息（表情等暂不支持）");
            return;
        }
        runtimeStatus.inbound();
        // 「机器人状态」页要能看到正在和谁对话、说的什么；只引用一条消息时把引用内容当内容
        String turnContent = !text.isBlank()
                ? text
                : hasVoice ? "（语音消息）" : (quoted.text().isBlank() ? "" : "（引用了：" + quoted.text() + "）");
        String turnId = runtimeStatus.beginTurn(channel, event.groupId(), event.userId(), turnContent);
        executor.submit(() -> {
            try {
                BotIdentity identity = identityResolver.resolve(event.userId());
                Long contextGroup = channel.hasGroupContext() ? event.groupId() : null;
                boolean platformFeature =
                        BotAccessPolicy.platformFeatureAllowed(identity, channel, contextGroup, settings);
                boolean openAiStatusAllowed =
                        BotAccessPolicy.openAiStatusAllowed(identity, channel, contextGroup, settings);
                if (channel == BotChannel.TEMP_SESSION) {
                    // 临时会话探针：确认 OneBot 实现是否带了发起群号（拿不到就按「不在指定群」处理）
                    log.info("收到群临时会话: user={}, 发起群={}, 平台数据={}, OpenAI状态={}",
                            event.userId(), event.groupId(), platformFeature, openAiStatusAllowed);
                }

                String effectiveText = text;
                if (hasVoice) {
                    if (!speechService.transcriptionAvailable()) {
                        if (text.isBlank()) {
                            sendVoiceFailure(event, group, turnId, identity, platformFeature, text,
                                    ownImageRefs.size(), 0, quotedImageRefs.size(), 0,
                                    "语音识别还没有启用，请先发文字消息。");
                            return;
                        }
                        log.info("语音识别未启用，只处理同一条消息里的文字: user={}", event.userId());
                    } else {
                        String transcript = speechService.transcribe(voiceInput).orElse("");
                        if (transcript.isBlank()) {
                            if (text.isBlank()) {
                                sendVoiceFailure(event, group, turnId, identity, platformFeature, text,
                                        ownImageRefs.size(), 0, quotedImageRefs.size(), 0,
                                        "这条语音没听清，麻烦再发一次，或者直接发文字。");
                                return;
                            }
                            log.info("语音识别失败，只处理同一条消息里的文字: user={}", event.userId());
                        } else {
                            effectiveText = joinText(text, transcript);
                        }
                    }
                }

                // 下载图片放在后台线程里做，不占用回调线程；自己发的与被引用的分开下，存档时能说清来源
                List<BotImageFetcher.FetchedImage> ownImages = imageFetcher.fetch(ownImageRefs);
                List<BotImageFetcher.FetchedImage> quotedImages = imageFetcher.fetch(quotedImageRefs);
                List<BotImageFetcher.FetchedImage> images = new ArrayList<>(ownImages);
                images.addAll(quotedImages);
                boolean hasImages = !ownImageRefs.isEmpty() || !quotedImageRefs.isEmpty();

                // 先存档用户消息：即使后面回答失败，也能查证「他说了什么、发了什么图/语音」
                String correlationId = archive.recordInbound(event,
                        describeIncoming(effectiveText, ownImageRefs.size(), ownImages.size(),
                                quotedImageRefs.size(), quotedImages.size(), hasVoice),
                        kindOf(effectiveText, settings, hasImages, hasVoice), identity);

                VoiceReplyIntent voiceReply = VoiceReplyIntent.parse(effectiveText);
                String requestText = voiceReply.requested() ? voiceReply.cleanedText() : effectiveText;
                if (requestText.isBlank()) {
                    requestText = "请用简短自然的方式回应我。";
                }

                GeneratedImages generated = new GeneratedImages();
                BotReport report;
                try {
                    report = answer(event, requestText, quoted, !quotedImages.isEmpty(), settings, identity,
                            platformFeature, openAiStatusAllowed, channel, images, generated, voiceReply);
                } catch (Exception ex) {
                    log.warn("处理 QQ 消息失败: user={}, platformUser={}, reason={}",
                            event.userId(), identity.platformUser(), ex.getMessage());
                    runtimeStatus.failed(event.userId(), ex.getMessage());
                    // 异常细节只回给有平台功能的会话，避免向陌生人泄露内部实现
                    report = BotReport.fromMarkdown(platformFeature
                            ? "处理这条消息时出错了：" + ex.getMessage()
                            : "处理这条消息时出错了，请稍后再试。");
                }

                // 群聊功能照常执行，但最终输出统一脱敏：不展示账号名称、上游平台名称/地址和邮箱。
                if (BotCommandRouter.inGroupContext(event)) {
                    report = groupRedactor.redact(report);
                }

                SendOutcome outcome;
                try {
                    outcome = send(event, group, report, settings, generated, voiceReply);
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

    /** 是否为违禁词管理命令或明确自然语言；用于管理员跨白名单群、免 @ 呼出。 */
    private static boolean isForbiddenWordManagementMessage(String text, BotSettings settings) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String prefix = settings.commandPrefix();
        if (prefix != null && !prefix.isBlank() && text.startsWith(prefix)) {
            return BotCommandRouter.isForbiddenWordManagementCommand(text.substring(prefix.length()).trim());
        }
        return BotForbiddenWordIntent.toCommandLine(text).isPresent();
    }

    /** 全渠道违禁词命中回复；身份解析失败也不影响发送拦截提示。 */
    private void replyForbiddenWord(OneBotEvent event, BotChannel channel, boolean group,
                                    String text, BotMessageParser.QuotedMessage quoted, BotIdentity identity) {
        String content = !text.isBlank()
                ? text
                : (quoted.text().isBlank() ? "[违禁词消息]" : "（引用了：" + quoted.text() + "）");
        runtimeStatus.inbound();
        String turnId = runtimeStatus.beginTurn(channel, event.groupId(), event.userId(), content);
        executor.submit(() -> {
            try {
                String correlationId = archive.recordInbound(event, content,
                        BotMessageArchiveService.KIND_TEXT, identity);
                sendText(event, group, FORBIDDEN_WORD_REPLY);
                runtimeStatus.replied();
                runtimeStatus.finishTurn(turnId, FORBIDDEN_WORD_REPLY);
                archive.recordOutbound(event, FORBIDDEN_WORD_REPLY, BotMessageArchiveService.KIND_TEXT,
                        identity, null, correlationId);
                log.info("违禁词命中，已拒绝回答: channel={}, group={}, user={}",
                        channel, event.groupId(), event.userId());
            } catch (RuntimeException ex) {
                runtimeStatus.failed(event.userId(), ex.getMessage());
                runtimeStatus.finishTurn(turnId, "发送违禁词提示失败：" + ex.getMessage());
                log.warn("发送违禁词拦截提示失败: user={}, reason={}", event.userId(), ex.getMessage());
            }
        });
    }

    private BotReport answer(OneBotEvent event, String text, BotMessageParser.QuotedMessage quoted,
                             boolean quotedHasImages, BotSettings settings, BotIdentity identity,
                             boolean platformFeature, boolean openAiStatusAllowed, BotChannel channel,
                             List<BotImageFetcher.FetchedImage> images, GeneratedImages generated,
                             VoiceReplyIntent voiceReply) {
        String prefix = settings.commandPrefix();
        String commandLine = !text.isBlank() && prefix != null && !prefix.isBlank() && text.startsWith(prefix)
                ? text.substring(prefix.length()).trim() : null;

        // 违禁词管理不要求「指定群」，任意已启用群或管理员私聊都可以维护。
        boolean canManageForbiddenWords = identity.admin();
        // 管理命令本身必然会包含待添加/删除的词，因此对有权执行的管理员先于过滤处理。
        if (canManageForbiddenWords && BotCommandRouter.isForbiddenWordManagementCommand(commandLine)) {
            return commandRouter.route(commandLine, identity, platformFeature,
                    openAiStatusAllowed, event, generated, () -> notifyImageStart(event, generated));
        }
        // 管理员也可以直接用自然语言维护；只执行意图明确的短句，避免误操作。
        if (canManageForbiddenWords && commandLine == null) {
            var naturalLanguageCommand = BotForbiddenWordIntent.toCommandLine(text);
            if (naturalLanguageCommand.isPresent()) {
                return commandRouter.route(naturalLanguageCommand.get(), identity, platformFeature,
                        openAiStatusAllowed, event, generated, () -> notifyImageStart(event, generated));
            }
        }
        // 用户原文、引用内容、语音转写已在 text 中合并；普通用户任一命中都不进入大模型。
        if (!identity.admin() && (forbiddenWordService.containsForbiddenWord(text)
                || forbiddenWordService.containsForbiddenWord(quoted.text()))) {
            return text(FORBIDDEN_WORD_REPLY);
        }

        // 只发图片时 text 为空，不能当命令处理，直接交给模型
        if (commandLine != null) {
            return commandRouter.route(commandLine, identity, platformFeature,
                    openAiStatusAllowed, event, generated, () -> notifyImageStart(event, generated));
        }
        return askModel(event, text, quoted, quotedHasImages, settings, identity, platformFeature,
                openAiStatusAllowed, channel, images, generated, voiceReply);
    }

    /** 纯文本包成报表：短内容仍按纯文本发出，长内容走段落渲染。 */
    private static BotReport text(String value) {
        return BotReport.fromMarkdown(value);
    }

    private BotReport askModel(OneBotEvent event, String text, BotMessageParser.QuotedMessage quoted,
                               boolean quotedHasImages, BotSettings settings, BotIdentity identity,
                               boolean platformFeature, boolean openAiStatusAllowed, BotChannel channel,
                               List<BotImageFetcher.FetchedImage> images, GeneratedImages generated,
                               VoiceReplyIntent voiceReply) {
        BotChatClientFactory.Persona persona = personaOf(identity, platformFeature);
        ChatClient chatClient = chatClients.get(persona, settings, identity.admin());
        if (chatClient == null) {
            String message = images.isEmpty()
                    ? "自然语言问答未启用（需要在配置里打开大模型）。当前可用命令：\n"
                    + commandRouter.help(identity, platformFeature, openAiStatusAllowed,
                    BotCommandRouter.inGroupContext(event))
                    : "看图片需要开启大模型（SPRING_AI_MODEL_CHAT）。当前可用命令：\n"
                    + commandRouter.help(identity, platformFeature, openAiStatusAllowed,
                    BotCommandRouter.inGroupContext(event));
            return text(message);
        }
        String promptText = parser.buildPrompt(text, quoted.text(), !images.isEmpty(), quotedHasImages);
        if (voiceReply != null && voiceReply.requested()) {
            String style = voiceReply.style() == null || voiceReply.style().isBlank()
                    ? settings.defaultVoiceStyle() : voiceReply.style();
            if (style != null && !style.isBlank()) {
                promptText = "（请用" + style + "的口吻组织回答；回答稍后会转成语音，不要提及这段说明。）\n" + promptText;
            }
        }
        String finalPromptText = promptText;
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .user(spec -> {
                    spec.text(finalPromptText);
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
            request = request.tools(new BotUserTools(botUserService, botKeyUsageService, identity.email()));
            // 普通用户只能查自己的错误；工具在构造时绑定邮箱，不接受用户标识参数。
            request = request.tools(new BotMyErrorTools(errorService, identity.email(), errorQueryGuard, errorQueryAuditService));
        }
        // 管理员余额调整：按请求绑定管理员身份和当前消息，二次确认必须出现在当前消息里
        if (platformFeature && identity.admin() && identity.hasEmail()) {
            request = request.tools(new BotBalanceTools(botBalanceService, identity.email(), text));
        }
        // 渠道状态在指定群和好友私聊中开放；群内最终输出由脱敏器处理
        if (platformFeature) {
            request = request.tools(new BotChannelStatusTools(
                    channelStatusService, identity.admin(), commandRouter.viewerUserId(identity)));
        }
        // OpenAI 官方状态是公开信息，和上游账号数据分开授权。
        if (openAiStatusAllowed) {
            request = request.tools(new OpenAiStatusTools(openAiStatusService));
        }
        // 生图：所有人都能用，图片归属到本条消息（按请求构造，避免串到别人的回复里）
        request = request.tools(new ImageGenTools(imageGenService, generated,
                    () -> notifyImageStart(event, generated)));
        // ToolContext 必须非空；即使事件缺少 userId 也放入空字符串，工具侧会按未知用户处理。
        request = request.toolContext(Map.of("qqUserId",
                event.userId() == null ? "" : event.userId()));
        ChatClient.ChatClientRequestSpec finalRequest = request
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId(event, persona, channel)));
        String answer;
        try {
            answer = finalRequest.call().content();
        } catch (RuntimeException ex) {
            if (!isSseConversionError(ex)) {
                throw ex;
            }
            log.warn("大模型接口返回 SSE 而当前请求为非流式，自动降级为流式聚合并继续处理: user={}", event.userId());
            java.util.List<String> chunks = finalRequest.stream().content()
                    .collectList()
                    .block(java.time.Duration.ofMinutes(2));
            answer = chunks == null ? "" : String.join("", chunks);
        }

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

    /** 上游偶发把非流式请求按 SSE 返回；识别后改用 Spring AI 的流式接口兜底。 */
    static boolean isSseConversionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains("text/event-stream")
                    && message.contains("HttpMessageConverter")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
     * <p>用户明确要求语音回复时，优先发送 MOSS-TTSD 合成的语音；语音不可用或发送失败时
     * 退回文字，保证用户至少能收到答案。长回答只朗读开头，并补发完整文字。</p>
     */
    private SendOutcome send(OneBotEvent event, boolean group, BotReport report, BotSettings settings,
                            GeneratedImages generated, VoiceReplyIntent voiceReply) {
        int maxReplyLength = settings.maxReplyLength();
        List<byte[]> generatedImages = generated.snapshot();
        // 先生成的图：这是用户明确要的东西，发失败要让他知道，而不是只回一句文字
        for (byte[] image : generatedImages) {
            if (sendImage(event, group, image)) {
                log.info("已发送生成的图片: user={}, bytes={}", event.userId(), image.length);
            } else {
                sendText(event, group, "图片发送失败了，请稍后再试。");
            }
        }
        String plain = report.toMobileText();
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

        if (voiceReply != null && voiceReply.requested()) {
            if (!speechService.synthesisAvailable()) {
                sendText(event, group, "语音回复还没有启用，先用文字回复：\n" + truncated);
                return new SendOutcome(archiveContent(truncated, generatedImages), BotMessageArchiveService.KIND_TEXT);
            }
            String voice = voiceReply.voice() == null || voiceReply.voice().isBlank()
                    ? settings.defaultVoice() : voiceReply.voice();
            var audio = speechService.synthesize(plain, voice);
            if (audio.isPresent() && sendVoice(event, group, audio.get())) {
                log.info("已发送语音回复: user={}, voice={}, bytes={}", event.userId(), voice, audio.get().length);
                if (plain.length() > speechService.maxTtsChars()) {
                    // 语音只朗读开头，长表格/长报告再补发完整文字
                    sendText(event, group, truncated);
                }
                return new SendOutcome(archiveContent(plain, generatedImages), BotMessageArchiveService.KIND_VOICE);
            }
            sendText(event, group, "语音发送失败了，先用文字回复：\n" + truncated);
            return new SendOutcome(archiveContent(truncated, generatedImages), BotMessageArchiveService.KIND_TEXT);
        }

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

    /** 语音识别不可用或没听清时，仍然给用户一个明确回复并完成存档。 */
    private void sendVoiceFailure(OneBotEvent event, boolean group, String turnId, BotIdentity identity,
                                  boolean platformFeature, String text, int ownTotal, int ownFetched,
                                  int quotedTotal, int quotedFetched, String reply) {
        String correlationId = archive.recordInbound(event,
                describeIncoming(text, ownTotal, ownFetched, quotedTotal, quotedFetched, true),
                BotMessageArchiveService.KIND_VOICE, identity);
        sendText(event, group, reply);
        runtimeStatus.replied();
        runtimeStatus.finishTurn(turnId, reply);
        archive.recordOutbound(event, reply, BotMessageArchiveService.KIND_TEXT, identity,
                personaOf(identity, platformFeature).name(), correlationId);
    }

    /** 合并同一条消息里的文字与语音转写结果。 */
    private static String joinText(String text, String transcript) {
        if (text == null || text.isBlank()) {
            return transcript == null ? "" : transcript.trim();
        }
        if (transcript == null || transcript.isBlank()) {
            return text.trim();
        }
        return text.trim() + "\n" + transcript.trim();
    }

    /** 判断这条用户消息的形态，用于存档分类。 */
    private static String kindOf(String text, BotSettings settings, boolean hasImages, boolean hasVoice) {
        if (hasVoice) {
            return BotMessageArchiveService.KIND_VOICE;
        }
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
                                           int quotedTotal, int quotedFetched, boolean hasVoice) {
        StringBuilder sb = new StringBuilder();
        if (hasVoice) {
            sb.append("[语音]");
        }
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

    private boolean sendVoice(OneBotEvent event, boolean group, byte[] audio) {
        return group
                ? client.sendGroupVoice(event.groupId(), audio)
                : client.sendPrivateVoice(event.userId(), audio);
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
