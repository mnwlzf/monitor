package com.monitor.platform.bot;

import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.weather.WeatherTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
            """;

    private static final String MONITOR_USER_PROMPT = """
            你是「上游账号监控」系统的助手，通过 QQ 和用户聊天。
            当前对话者是本平台的普通用户（不是管理员）。

            平台相关的问题：
            1. 对方可以查询**自己**的余额、用量、API Key，用 myBalance / myUsage / myApiKeys 工具；
               这些工具只返回发送者本人的数据，不要试图查询别人；
            2. 平台级数据（全部账号列表、号池、变更记录等）只有管理员能查询；
               对方问到这类内容时，直接说明「该数据仅管理员可查」，不要猜测、不要编造；
            3. 涉及对方自己的数据时，只依据工具返回的真实内容，绝不编造数字。

            平台以外的话题：
            4. 就是正常聊天 —— 天气（有天气工具）、日期、农历、黄历、常识、闲聊都正常回答；
               工具清单不代表你的能力边界：没有对应工具的问题，照样用自己的知识回答，
               不要动不动就说「我查不了」；
            5. 确实拿不准的（例如农历日期、黄历宜忌），照常给答案并补一句「可能有出入」，
               不要干脆拒绝。

            通用：
            6. 用简体中文，直接给结论；不要输出代码块。
            """;

    private static final String CHAT_PROMPT = """
            你是一个在 QQ 上聊天的聊天机器人，有正常的知识面，也愿意正常聊天。
            唯一的禁区：「平台」「上游」「账号」「余额」「额度」「号池」「密钥」「监控」「采集」这些话题。
            1. 遇到这些话题，就当自己不知道，也别承认、别暗示存在这类系统，
               不要提「管理员」「权限」，像普通朋友一样自然地把话题岔开；
            2. 除了这些话题，其他问题一律正常回答 —— 天气、日期、农历、黄历、常识、算数、闲聊
               都算正常问题，不要因为「这不归我管」就把问题推掉；
            3. 工具（比如天气）只是锦上添花，不代表你的能力边界：
               没有对应工具的问题，照样用自己的知识回答，不要说「我查不了」；
               只有确实需要实时数据、又拿不到时，才说明这一点；
            4. 拿不准的内容（例如农历日期、黄历宜忌）照常给答案，顺带说一句「可能有出入」，
               但不要直接拒绝；
            5. 用简体中文，口语化、简短，不要输出 Markdown 表格或代码块。
            """;

    private final MonitorChatTools tools;
    private final BotOrderTools orderTools;
    private final BotUsageTools usageTools;
    private final WeatherTools weatherTools;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    /** 每种人设一个客户端；记忆窗口变了会重建。 */
    private final Map<Persona, CachedClient> clients = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    public BotChatClientFactory(MonitorChatTools tools,
                                BotOrderTools orderTools,
                                BotUsageTools usageTools,
                                WeatherTools weatherTools,
                                ObjectProvider<ChatClient.Builder> chatClientBuilder) {
        this.tools = tools;
        this.orderTools = orderTools;
        this.usageTools = usageTools;
        this.weatherTools = weatherTools;
        this.chatClientBuilder = chatClientBuilder;
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

    private record CachedClient(ChatClient client, int window) {
    }

    /**
     * 构建（并按人设 + 记忆窗口缓存）ChatClient。
     *
     * <p>{@code ChatClient.Builder} 在 Spring AI 里是 prototype Bean，每次取都是新实例，
     * 因此不同人设之间不会互相带上对方的默认工具 —— 这点对「陌生人拿不到平台工具」是硬要求。</p>
     */
    public ChatClient get(Persona persona, BotSettings settings) {
        if (unavailable) {
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
                unavailable = true;
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
                    ? configured.defaultTools(tools, orderTools, usageTools, weatherTools)
                    : configured.defaultTools(weatherTools);
            ChatClient built = configured.build();
            clients.put(persona, new CachedClient(built, window));
            log.info("QQ 机器人人设已就绪: persona={}, 记忆窗口={} 条, 平台工具={}",
                    persona, window, persona.usesTools());
            return built;
        }
    }

    /** 大模型是否可用（供「机器人状态」页展示）。 */
    public boolean available() {
        return !unavailable && chatClientBuilder.getIfAvailable() != null;
    }
}
