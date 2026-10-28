package com.monitor.platform.bot;

import com.monitor.platform.bot.error.BotErrorTools;
import com.monitor.platform.bot.key.Sub2KeyTools;
import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.search.WebSearchProperties;
import com.monitor.platform.bot.search.WebSearchTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.weather.WeatherTools;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人人设与 {@link ChatClient} 的构建与缓存。
 *
 * <p>三个人设（管理员 / 平台普通用户 / 陌生人）各自一套系统提示词与工具集，
 * 提示词里带「陌生人绝不能看到平台信息」的硬约束，因此必须按人设隔离客户端 ——
 * 见 {@code Persona} 与 {@code get} 的说明。</p>
 *
 * <p>从 {@code BotMessageService} 抽出：消息处理负责「判断该用哪个人设」，
 * 本类负责「按人设把客户端建出来并缓存」。</p>
 */
@Component
public class BotChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(BotChatClientFactory.class);

    private static final String MONITOR_ADMIN_PROMPT = """
            你是「上游账号监控」系统的运维助手，通过 QQ 回答用户关于平台、账号、号池的问题。
            当前对话者是平台管理员，可以查看全部平台数据。
            要求：
            1. 只依据工具返回的真实数据回答，绝不编造数字；
            2. 用简体中文，直接给结论；
            3. 需要罗列多条数据时用 Markdown 表格（| 列名 | 列名 | 加一行 |---|），
               系统会把表格渲染成图片，比一长串项目符号好读；不要输出代码块；
               表格必须「一行一个数据点」，别为了少几行把数据挤在一起：
               * 一条渠道变更对应多个本平台分组时写多行，每行只放一个分组；
                 禁止把多条变更合并成一行，也不要写「（3 条）」这类合并写法；
               * 「本平台账号」「本平台分组」「分组倍率」必须各占一列；禁止把多个分组或
                 倍率用「/」「、」挤进同一个单元格，也不要自创「对应本平台分组倍率」
                 这种合并列 —— 挤在一起既难核对，也找不到数据；
               * 工具返回的「本平台账号 / 本平台分组 / 分组倍率 / 是否受影响」几列原样保留；
            4. 涉及号池趋势、热力图、时间分布时，必须调用 poolHeatmap 工具，
               并把它返回的 [[HEATMAP:...]] 标记原样放进回答；
               系统会在标记处渲染出真正的热力图。不要自己画表格、色块或 emoji ——
               矩阵太大，你自己重写会和页面数据对不上；
            5. 涉及指标时说明时间窗（例如「近 24 小时」）；
            6. 回答「倍率倒挂 / 哪些渠道倒挂 / 是否亏本」时，固定口径为：
               上游渠道新倍率 > 本平台分组倍率 即为倒挂；只看分组倍率，
               不要再乘账号倍率，也不要因为缺少账号倍率而说无法计算。
               工具返回的「是否受影响=是（倒挂）」直接作为结论。
            7. 工具查不到就直说查不到，不要猜测。
            8. 只有需要外部实时信息（新闻、版本发布、价格、官方文档、事件等）时才用 webSearch；
               摘要不够再用 webFetch 抓具体链接。问本系统/机器人的功能、命令、权限、账号、
               余额、号池、变更记录时，直接依据这里的说明或平台工具回答，不要联网搜索，
               也不要编造外部链接。
            9. 管理员要求给平台用户增加余额时：
               * 用户 ID、邮箱、用户名一律走模糊匹配，先调用 findBalanceRechargeCandidates 返回候选，禁止自动选人；
               * 管理员确认具体人员后，调用 previewBalanceIncreaseForUser(userId...) 生成预览，禁止直接声称已充值；
               * 只有管理员在下一条消息明确回复「确认充值 <确认码>」后，才调用 confirmBalanceIncrease；
               * 余额操作只允许增加，禁止 set / subtract；成功后如实返回充值前后余额与累计充值。
            10. 查错误时优先使用 diagnoseErrors / recentErrors / topErrorAccounts / errorsByUser / topErrorFingerprints / errorTrend / errorByInstances / recentErrorChanges / recoveredUpstreamErrors / ingressRejections / recentSystemLogs / errorQueryAudit，
                不要要求用户提供请求 ID。用户端看不到请求 ID；需要单条详情时使用机器人返回的「错误编号 #数字」。
            11. 查询 OpenAI / ChatGPT / Codex 官方服务状态时调用 openaiStatus 工具，并用中文回答；原样保留工具返回的「查询时间」，它表示本次实时读取状态页的时间。
            """;

    private static final String MONITOR_USER_PROMPT = """
            你是「上游账号监控」系统的助手，通过 QQ 和用户聊天。
            当前对话者是本平台的普通用户（不是管理员）。

            平台相关的问题：
            1. 对方可以查询**自己**的余额、用量、API Key、最近错误，用 myBalance / myUsage / myApiKeys / myRecentErrors 工具；
               这些工具只返回发送者本人的数据，不要试图查询别人；
            2. 平台级数据（全部账号列表、号池、变更记录等）只有管理员能查询；
               对方问到这类内容时，直接说明「该数据仅管理员可查」，不要猜测、不要编造；
            3. 涉及对方自己的数据时，只依据工具返回的真实内容，绝不编造数字；
            4. OpenAI / ChatGPT / Codex 官方服务状态是公开信息，可以调用 openaiStatus 工具查询，并必须用中文回答；原样保留工具返回的「查询时间」，它表示本次实时读取状态页的时间。

            平台以外的话题：
            5. 就是正常聊天 —— 天气（有天气工具）、日期、农历、黄历、常识、闲聊都正常回答；
               工具清单不代表你的能力边界：没有对应工具的问题，照样用自己的知识回答，
               不要动不动就说「我查不了」；
            6. 确实拿不准的（例如农历日期、黄历宜忌），照常给答案并补一句「可能有出入」，
               不要干脆拒绝。

            通用：
            7. 用简体中文，直接给结论；不要输出代码块。
            8. 在群聊或群临时会话中，不要直接写账号名称、上游平台名称或平台地址；统一用「上游账号」「上游平台」代替。
            """;

    private static final String CHAT_PROMPT = """
            你是一个在 QQ 上聊天的聊天机器人，有正常的知识面，也愿意正常聊天。
            唯一的禁区：本系统的上游平台、账号、余额、额度、号池、密钥、API Key、渠道、变更、采集、监控、订单、用量、错误等私有信息。
            尤其在群聊和群临时会话中，任何情况下都不要展示、确认或暗示这些信息。
            1. 遇到这些话题，就当自己不知道，也别承认、别暗示存在这类系统，
               不要提「管理员」「权限」，像普通朋友一样自然地把话题岔开；
            2. 除了这些话题，其他问题一律正常回答 —— 天气、日期、农历、黄历、常识、算数、闲聊
               都算正常问题，不要因为「这不归我管」就把问题推掉；
            3. 工具（比如天气）只是锦上添花，不代表你的能力边界：
               没有对应工具的问题，照样用自己的知识回答，不要说「我查不了」；
               只有确实需要实时数据、又拿不到时，才说明这一点；
            4. 拿不准的内容（例如农历日期、黄历宜忌）照常给答案，顺带说一句「可能有出入」，
               但不要直接拒绝；
            5. OpenAI / ChatGPT / Codex 官方服务状态属于公开信息，不属于上述禁区；有 openaiStatus 工具时按工具结果用中文回答。
            6. 用简体中文，口语化、简短，不要输出 Markdown 表格或代码块。
            """;

    private final MonitorChatTools tools;
    private final BotErrorTools errorTools;
    private final BotOrderTools orderTools;
    private final BotUsageTools usageTools;
    private final WeatherTools weatherTools;
    private final WebSearchTools webSearchTools;
    private final Sub2KeyTools sub2KeyTools;
    private final WebSearchProperties webSearchProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final BotModelFallbackProperties fallbackProperties;

    /** 每种「人设 + 是否管理员」一个客户端；记忆窗口 / 联网搜索开关变了会重建。 */
    private final Map<ClientKey, CachedClient> clients = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile boolean fallbackUnavailable;
    private volatile ChatClient.Builder fallbackBuilder;

    public BotChatClientFactory(MonitorChatTools tools,
                                BotErrorTools errorTools,
                                BotOrderTools orderTools,
                                BotUsageTools usageTools,
                                WeatherTools weatherTools,
                                WebSearchTools webSearchTools,
                                Sub2KeyTools sub2KeyTools,
                                WebSearchProperties webSearchProperties,
                                ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                BotModelFallbackProperties fallbackProperties) {
        this.tools = tools;
        this.errorTools = errorTools;
        this.orderTools = orderTools;
        this.usageTools = usageTools;
        this.weatherTools = weatherTools;
        this.webSearchTools = webSearchTools;
        this.sub2KeyTools = sub2KeyTools;
        this.webSearchProperties = webSearchProperties;
        this.chatClientBuilder = chatClientBuilder;
        this.fallbackProperties = fallbackProperties;
    }
    /**
     * 机器人人设。
     *
     * <p>提示词在方法里取而不是构造器参数：枚举常量先于类的静态字段初始化，
     * 直接引用 {@code MONITOR_ADMIN_PROMPT} 会拿到 null。</p>
     */
    public enum Persona {
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

    private record CachedClient(ChatClient client, int window, boolean webSearch) {
    }

    /** 客户端缓存键：人设 + 是否管理员（管理员即使人设退化成 CHAT，也要有联网搜索）。 */
    private record ClientKey(Persona persona, boolean admin, boolean fallback) {
    }

    /**
     * 构建（并按人设 + 记忆窗口缓存）ChatClient。
     *
     * <p>{@code ChatClient.Builder} 在 Spring AI 里是 prototype Bean，每次取都是新实例，
     * 因此不同人设之间不会互相带上对方的默认工具 —— 这点对「陌生人拿不到平台工具」是硬要求。</p>
     */
    public ChatClient get(Persona persona, BotSettings settings, boolean admin) {
        ChatClient primary = getClient(false, persona, settings, admin);
        if (primary != null) {
            return primary;
        }
        return getFallback(persona, settings, admin);
    }

    /** 获取备用模型客户端；未配置备用 Key 时返回 null。 */
    public ChatClient getFallback(Persona persona, BotSettings settings, boolean admin) {
        return getClient(true, persona, settings, admin);
    }

    private ChatClient getClient(boolean fallback, Persona persona, BotSettings settings, boolean admin) {
        if (fallback ? fallbackUnavailable : unavailable) {
            return null;
        }
        int window = Math.max(2, settings.memoryWindow());
        // 联网搜索开关来自页面设置（bot_settings），变了要重建客户端，不能只按记忆窗口缓存
        boolean attachWebSearch = shouldAttachWebSearch(admin, settings);
        ClientKey key = new ClientKey(persona, admin, fallback);
        CachedClient cached = clients.get(key);
        if (cached != null && cached.window() == window && cached.webSearch() == attachWebSearch) {
            return cached.client();
        }
        synchronized (this) {
            cached = clients.get(key);
            if (cached != null && cached.window() == window && cached.webSearch() == attachWebSearch) {
                return cached.client();
            }
            ChatClient.Builder builder = fallback ? fallbackBuilder() : chatClientBuilder.getIfAvailable();
            if (builder == null) {
                if (fallback) {
                    fallbackUnavailable = true;
                } else {
                    unavailable = true;
                }
                log.info(fallback
                        ? "未配置备用大模型"
                        : "未配置大模型，QQ 机器人只支持命令（/help 查看）");
                return null;
            }
            ChatClient.Builder configured = builder
                    .defaultSystem(persona.systemPrompt())
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(
                            MessageWindowChatMemory.builder().maxMessages(window).build())
                            .build());
            // 天气与平台无关，三种人设都能用（含陌生人）。
            if (persona.usesTools()) {
                // 管理员专属：建密钥工具只挂管理员人设，普通人 / 陌生人拿不到
                configured = attachWebSearch
                        ? configured.defaultTools(tools, errorTools, orderTools, usageTools, weatherTools, webSearchTools, sub2KeyTools)
                        : configured.defaultTools(tools, errorTools, orderTools, usageTools, weatherTools, sub2KeyTools);
            } else {
                configured = attachWebSearch
                        ? configured.defaultTools(weatherTools, webSearchTools)
                        : configured.defaultTools(weatherTools);
            }
            ChatClient built = configured.build();
            clients.put(key, new CachedClient(built, window, attachWebSearch));
            log.info("QQ 机器人{}人设已就绪: persona={}, admin={}, 记忆窗口={} 条, 平台工具={}, 联网搜索={}",
                    fallback ? "备用" : "", persona, admin, window, persona.usesTools(), attachWebSearch);
            return built;
        }
    }

    private ChatClient.Builder fallbackBuilder() {
        if (!fallbackProperties.isConfigured() || fallbackUnavailable) {
            return null;
        }
        ChatClient.Builder current = fallbackBuilder;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (fallbackBuilder != null) {
                return fallbackBuilder;
            }
            try {
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(fallbackProperties.getBaseUrl().trim())
                        .apiKey(fallbackProperties.getApiKey().trim())
                        .build();
                OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                        .model(fallbackProperties.getModel().trim());
                if (fallbackProperties.getTemperature() != null) {
                    options.temperature(fallbackProperties.getTemperature());
                }
                OpenAiChatModel model = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(options.build())
                        .toolCallingManager(ToolCallingManager.builder().build())
                        .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                        .observationRegistry(ObservationRegistry.NOOP)
                        .build();
                fallbackBuilder = ChatClient.builder(model);
                log.info("备用大模型已配置: baseUrl={}, model={}",
                        fallbackProperties.getBaseUrl(), fallbackProperties.getModel());
                return fallbackBuilder;
            } catch (RuntimeException ex) {
                fallbackUnavailable = true;
                log.warn("备用大模型初始化失败: {}", ex.getMessage());
                return null;
            }
        }
    }

    /** 是否为可切换到备用模型的调用故障。 */
    public boolean shouldFallback(Throwable error) {
        if (!hasFallback() || error == null) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof TransientAiException) {
                return true;
            }
            if (current instanceof HttpStatusCodeException statusError) {
                int status = statusError.getStatusCode().value();
                if (status == 401 || status == 403 || status == 404 || status == 429 || status >= 500) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("401") || lower.contains("403") || lower.contains("404")
                        || lower.contains("429") || lower.contains("500") || lower.contains("502")
                        || lower.contains("503") || lower.contains("504") || lower.contains("timeout")
                        || lower.contains("timed out") || lower.contains("connection")
                        || lower.contains("unauthorized") || lower.contains("forbidden")
                        || lower.contains("model_not_found") || lower.contains("does not exist")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public boolean hasFallback() {
        return fallbackBuilder() != null;
    }

    /**
     * 是否把联网搜索挂给这次请求。
     *
     * <p>按<strong>说话的人</strong>判断，而不是按人设：管理员即使不在指定群（人设会退化成 CHAT），
     * 联网搜索作为基础能力也照常可用；普通人由页面开关决定（且要求已配 Key，
     * 避免把「去配 Key」这类运维提示暴露给普通用户）。</p>
     *
     * <p><strong>与群无关</strong>：不看「指定群」；是否响应仍由「允许的群号 / 私聊 QQ」白名单决定。</p>
     */
    private boolean shouldAttachWebSearch(boolean admin, BotSettings settings) {
        if (!webSearchProperties.isEnabled()) {
            return false;
        }
        return admin
                || (settings.webSearchForNonAdmin() && webSearchProperties.isConfigured());
    }

    /** 大模型是否可用（供「机器人状态」页展示）。 */
    public boolean available() {
        return (!unavailable && chatClientBuilder.getIfAvailable() != null) || hasFallback();
    }
}
