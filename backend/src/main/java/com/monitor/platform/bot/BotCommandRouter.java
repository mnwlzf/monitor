package com.monitor.platform.bot;

import com.monitor.platform.bot.channel.BotChannelStatusService;
import com.monitor.platform.bot.error.BotErrorQueryAuditService;
import com.monitor.platform.bot.error.BotErrorQueryGuard;
import com.monitor.platform.bot.error.BotErrorService;
import com.monitor.platform.bot.error.BotInstanceErrorService;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.image.GeneratedImages;
import com.monitor.platform.bot.image.ImageGenService;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.weather.WeatherTools;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 确定性命令的路由与权限闸门：{@code /help}、{@code /绑定}、{@code /平台}、{@code /号池}……
 *
 * <p>从 {@link BotMessageService} 抽出：消息处理负责「收消息、判断走命令还是大模型、把结果发出去」，
 * 本类只负责「一条命令由谁执行、当前这个人能不能执行」。命令不依赖大模型，没配模型也能用。</p>
 *
 * <p>权限口径（实现见各闸门方法）：平台级数据仅管理员可查；用户级数据只查发送者本人；
 * 非平台用户一律回「未知命令」，不暴露功能存在。</p>
 */
@Component
public class BotCommandRouter {

    /** 邮箱格式校验：够用即可，不做 RFC 级校验。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final String ADMIN_HELP = """
            可用命令：
            - /平台            上游平台概览
            - /号池 [时间窗]   号池渠道的缓存率、首 Token、每秒 Token 与趋势矩阵
            - /采集            号池直连库增量采集状态
            - /账号 <关键字>   按名称查账号余额与状态
            - /变更 [条数|关键字]  最近的账号/密钥/渠道变更；「/变更 倍率」只看涨价
            - /消耗 [条数]     全平台账号消耗排行（余额 / 今日 / 累计）
            - /异常            停用、采集失败、凭证失效的账号
            - /诊断 [时间窗]   错误概览、主要问题和处理建议（默认近 1 小时）
            - /错误 [时间窗] [状态码|关键词]  最近错误；/错误 #编号 查看单条详情
            - /错误账号 [时间窗] [条数]  按账号统计错误排行
            - /错误用户 <邮箱|ID> [时间窗]  指定用户的错误
            - /错误指纹 [时间窗] [条数]  按错误阶段/类型/状态码/错误码聚合，查看影响面
            - /错误趋势 [时间窗]  当前窗口与上一周期对比，判断是否突增
            - /错误实例 [时间窗]  多个 Sub2API 实例的错误率/健康度对比
            - /错误变更 [时间窗]  查看错误时间窗内发生的分组/Key/账号变更，回答「是不是刚改了什么」
            - /恢复错误 [时间窗] [条数]  查看上游失败但最终恢复的请求和重试情况
            - /入口拒绝 [时间窗]  无效 Key、IP 限制、分组禁用等入口拒绝统计
            - /错误日志 [时间窗] [级别] [关键词]  按时间/级别/关键词查系统日志
            - /错误审计 [时间窗] [条数]  查看错误查询审计和限流拦截
            - /我的错误 [时间窗]  我自己的错误
            - /任务            定时任务启用状态与最近一次执行结果
            - /订单 <账号> [起] [止]  指定平台用户的订单（默认近 30 天）
            - /用户余额 <账号>       指定平台用户的余额与累计充值
            - 直接说「给用户 <ID/邮箱/用户名> 充值 <金额>」：先返回候选，选择用户 ID 后预览，再回复「确认充值 <确认码>」执行
            - /用户消耗 [日期]       所有用户在指定日期的余额消耗（默认今天）
            - /渠道状态 [时间窗]     各分组的渠道健康（对外口径，默认近 1 小时）
            - /我的余额        我自己的账户余额与状态
            - /我的用量 [窗]   我自己的用量（today / week / month）
            - /我的密钥        我自己的 API Key 列表
            - /我的信息        查看当前绑定的邮箱
            - /绑定 <邮箱>     绑定邮箱
            - /解绑            解除绑定
            - /天气 <城市>     实时天气与预报（所有人都能用）
            - /画图 <提示词>   生成一张图片（所有人都能用，全局一分钟最多 3 张）
            也可以直接说人话，例如「近 7 天哪个号池账号缓存率最低？」
            错误排障也可以直接问：「最近一小时错误最多的账号是谁？」「今天 401 多不多？」「现在系统有什么问题？」
            需要新建 Sub2API 密钥时，直接说清五项：厂商 / 名称 / 分组 / 额度限制 / 有效期（天数或 yyyy-MM-dd HH:mm:ss）。
            需要外部实时信息（新闻、版本发布、价格、官方文档）时，我会自动联网搜索。
            问机器人功能 / 命令时直接回答，不会联网。
            内容较长时会自动渲染成表格图片。""";

    private static final String USER_HELP = """
            可用命令：
            - /我的余额        我的账户余额与状态
            - /我的用量 [窗]   我的用量（today / week / month，默认 today）
            - /我的密钥        我的 API Key 列表
            - /我的信息        查看当前绑定的邮箱
            - /绑定 <邮箱>     绑定邮箱
            - /解绑            解除绑定
            - /天气 <城市>     实时天气与预报（所有人都能用）
            - /画图 <提示词>   生成一张图片（所有人都能用，全局一分钟最多 3 张）
            - /渠道状态 [时间窗]  我可用分组的渠道状态（默认近 1 小时）
            - /我的错误 [时间窗]  我自己的错误（默认近 24 小时）
            平台级数据（账号、号池、变更记录）仅管理员可查。""";

    private static final String GUEST_HELP = """
            我能做的事：
            - /绑定 <邮箱>     绑定你的邮箱
            - /解绑            解除绑定
            - /我的信息        查看当前绑定
            - /天气 <城市>     实时天气与预报
            - /画图 <提示词>   生成一张图片
            也可以直接和我聊天。""";

    /** 非平台用户看到「未知命令」时的统一回复，不暴露平台功能的存在。 */
    private static final String UNKNOWN_COMMAND = "这个命令我用不了，发送 /help 看看能做什么。";

    private final MonitorChatTools tools;
    private final BotOrderTools orderTools;
    private final BotUsageTools usageTools;
    private final BotChannelStatusService channelStatusService;
    private final BotErrorService errorService;
    private final BotInstanceErrorService instanceErrorService;
    private final BotErrorQueryGuard errorQueryGuard;
    private final BotErrorQueryAuditService errorQueryAuditService;
    private final BotUserService botUserService;
    private final WeatherTools weatherTools;
    private final QqUserBindingService bindings;
    private final Sub2ApiUserCache userCache;
    private final ImageGenService imageGenService;

    public BotCommandRouter(MonitorChatTools tools,
                            BotOrderTools orderTools,
                            BotUsageTools usageTools,
                            BotChannelStatusService channelStatusService,
                            BotErrorService errorService,
                            BotInstanceErrorService instanceErrorService,
                            BotErrorQueryGuard errorQueryGuard,
                            BotErrorQueryAuditService errorQueryAuditService,
                            BotUserService botUserService,
                            WeatherTools weatherTools,
                            QqUserBindingService bindings,
                            Sub2ApiUserCache userCache,
                            ImageGenService imageGenService) {
        this.tools = tools;
        this.orderTools = orderTools;
        this.usageTools = usageTools;
        this.channelStatusService = channelStatusService;
        this.errorService = errorService;
        this.instanceErrorService = instanceErrorService;
        this.errorQueryGuard = errorQueryGuard;
        this.errorQueryAuditService = errorQueryAuditService;
        this.botUserService = botUserService;
        this.weatherTools = weatherTools;
        this.bindings = bindings;
        this.userCache = userCache;
        this.imageGenService = imageGenService;
    }

    /**
     * 路由一条确定性命令。
     *
     * @param imageStartNotice 生图开始前的「正在画」提示。命令路由不直接碰 OneBot，
     *                         由负责发消息的 {@link BotMessageService} 执行，
     *                         并且只在通过限频、真的要开始画时触发。
     */
    public BotReport route(String commandLine, BotIdentity identity, boolean platformFeature,
                           OneBotEvent event, GeneratedImages generated, Runnable imageStartNotice) {
        String[] parts = commandLine.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (name) {
            case "help", "帮助", "?" -> text(help(identity, platformFeature));
            case "bind", "绑定" -> text(bind(arg, event));
            case "unbind", "解绑" -> text(unbind(event));
            case "whoami", "我的信息", "me" -> text(whoami(identity));

            // 用户级平台功能：只能查「自己」的数据（需要命中指定群 + 邮箱）
            case "我的余额", "余额", "balance" ->
                    selfService(platformFeature, identity, () -> botUserService.balanceReport(identity.email()));
            case "我的用量", "用量", "usage" ->
                    selfService(platformFeature, identity, () -> botUserService.usageReport(identity.email(), arg));
            case "我的密钥", "密钥", "keys" ->
                    selfService(platformFeature, identity, () -> botUserService.apiKeysReport(identity.email()));

            // 天气与平台无关，所有人（含陌生人）都能用
            case "weather", "天气" -> weather(arg);

            // 生图同样与平台无关，所有人都能用；限频在 ImageGenService 里做（全局一分钟 3 张）
            case "draw", "画图", "生图" -> draw(arg, generated, imageStartNotice);

            // 渠道状态：对外功能，管理员与平台用户都能看；非平台用户按未知命令处理
            case "渠道状态", "channelstatus" -> platformFeature
                    ? channelStatusService.report(identity.admin(), viewerUserId(identity), arg)
                    : text(UNKNOWN_COMMAND);

            // 平台级命令：没有平台功能（未命中指定群 / 陌生人）一律按「未知命令」处理，不暴露功能存在
            case "platform", "平台", "overview" -> platformOnly(identity, platformFeature, tools::platformReport);
            case "pool", "号池" -> platformOnly(identity, platformFeature, () -> tools.poolReport(arg));
            case "ingest", "采集" -> platformOnly(identity, platformFeature, tools::ingestReport);
            case "account", "账号" -> platformOnly(identity, platformFeature, () -> tools.accountReport(arg));
            case "changes", "变更" -> platformOnly(identity, platformFeature, () -> tools.changesReport(arg, 5));
            case "cost", "消耗", "用量榜" ->
                    platformOnly(identity, platformFeature, () -> tools.usageReport(parseInt(arg, 10)));
            case "abnormal", "异常" -> platformOnly(identity, platformFeature, tools::abnormalReport);
            case "diagnose", "诊断", "错误诊断" ->
                    errorCommand(event, identity, platformFeature, "diagnose", arg,
                            () -> errorService.diagnoseReport(arg));
            case "errors", "错误", "最近错误" ->
                    errorCommand(event, identity, platformFeature, "errors", arg,
                            () -> errorService.recentReport(arg));
            case "erroraccounts", "错误账号" ->
                    errorCommand(event, identity, platformFeature, "error-accounts", arg,
                            () -> errorService.accountReport(arg));
            case "erroruser", "错误用户" ->
                    errorCommand(event, identity, platformFeature, "error-user", arg,
                            () -> errorUserReport(arg));
            case "errorfingerprints", "错误指纹" ->
                    errorCommand(event, identity, platformFeature, "error-fingerprints", arg,
                            () -> errorService.fingerprintReport(arg));
            case "errortrend", "错误趋势" ->
                    errorCommand(event, identity, platformFeature, "error-trend", arg,
                            () -> errorService.trendReport(arg));
            case "errorinstances", "错误实例", "实例诊断" ->
                    errorCommand(event, identity, platformFeature, "error-instances", arg,
                            () -> instanceErrorService.report(arg));
            case "errorchanges", "错误变更", "什么变了" ->
                    errorCommand(event, identity, platformFeature, "error-changes", arg,
                            () -> errorService.changeReport(arg));
            case "recoverederrors", "恢复错误", "上游恢复" ->
                    errorCommand(event, identity, platformFeature, "recovered-errors", arg,
                            () -> errorService.recoveredReport(arg));
            case "ingress", "入口拒绝" ->
                    errorCommand(event, identity, platformFeature, "ingress", arg,
                            () -> errorService.ingressReport(arg));
            case "errorlogs", "错误日志" ->
                    errorCommand(event, identity, platformFeature, "error-logs", arg,
                            () -> errorService.systemLogReport(arg));
            case "erroraudit", "错误审计" ->
                    errorCommand(event, identity, platformFeature, "error-audit", arg,
                            () -> errorQueryAuditService.report(arg));
            case "myerrors", "我的错误" ->
                    selfErrorCommand(event, identity, platformFeature, arg,
                            () -> errorService.myReport(identity.email(), arg));
            case "tasks", "任务" -> platformOnly(identity, platformFeature, tools::taskReport);
            case "订单", "orders" -> platformOnly(identity, platformFeature, () -> orderReport(arg));
            case "用户余额", "userbalance" ->
                    platformOnly(identity, platformFeature, () -> orderTools.userBalanceReport(arg.trim()));
            case "用户消耗", "userconsumption" ->
                    platformOnly(identity, platformFeature, () -> usageTools.userConsumptionReport(arg.trim()));

            default -> text(platformFeature
                    ? "未知命令：" + name + "\n" + help(identity, platformFeature)
                    : UNKNOWN_COMMAND);
        };
    }

    /**
     * 命令帮助文本：按「有没有平台功能 + 是不是管理员」分档。
     *
     * <p>大模型不可用时会把它拼进「自然语言问答未启用」的提示里，所以包内可见。</p>
     */
    String help(BotIdentity identity, boolean platformFeature) {
        if (!platformFeature) {
            return GUEST_HELP;
        }
        return identity.admin() ? ADMIN_HELP : USER_HELP;
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

    /**
     * 用户级平台功能的统一闸门：查「自己」的数据。
     *
     * <p>没有平台功能（未命中指定群 / 陌生人）时，和平台级命令一样回「未知命令」，
     * 不暴露功能存在。邮箱在解析身份时就已确定，不接受任何用户标识参数。</p>
     */
    private BotReport selfService(boolean platformFeature, BotIdentity identity, Supplier<BotReport> action) {
        if (!platformFeature || !identity.hasEmail()) {
            return text(UNKNOWN_COMMAND);
        }
        return action.get();
    }

    /** 管理员错误命令的统一审计和限流闸门。 */
    private BotReport errorCommand(OneBotEvent event, BotIdentity identity, boolean platformFeature,
                                   String action, String query, Supplier<BotReport> call) {
        if (!platformFeature) {
            return text(UNKNOWN_COMMAND);
        }
        if (!identity.admin()) {
            errorQueryAuditService.record(event.userId(), action, query, false, 0);
            return text("平台级数据仅管理员可查。");
        }
        return guardedErrorCommand(event, action, query, call);
    }

    /** 普通用户自己的错误查询：同样限流和审计，但只允许查本人。 */
    private BotReport selfErrorCommand(OneBotEvent event, BotIdentity identity, boolean platformFeature,
                                       String query, Supplier<BotReport> call) {
        if (!platformFeature || !identity.hasEmail()) {
            return text(UNKNOWN_COMMAND);
        }
        return guardedErrorCommand(event, "my-errors", query, call);
    }

    private BotReport guardedErrorCommand(OneBotEvent event, String action, String query,
                                          Supplier<BotReport> call) {
        Long userId = event.userId();
        long id = userId == null ? 0L : userId;
        if (!errorQueryGuard.allow(id)) {
            errorQueryAuditService.record(userId, action, query, false, 0);
            return text("错误查询太频繁，请稍后再试。");
        }
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            errorQueryAuditService.record(userId, action, query, true, durationMs);
        }
    }

    /** 纯文本包成报表：短内容仍按纯文本发出，长内容走段落渲染。 */
    private static BotReport text(String value) {
        return BotReport.fromMarkdown(value);
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

    /**
     * 当前看渠道状态的人的可见范围。
     *
     * <p>管理员返回 {@code null}（不过滤）；普通用户返回自己的 Sub2API 用户 ID，
     * 由 {@link Sub2ApiUserCache} 从邮箱解析（Redis 缓存，与用户端取数是同一条链路）。</p>
     *
     * <p>命令（{@code /渠道状态}）与大模型工具两条链路共用这一份口径，
     * 避免「谁能看哪些分组」各算一次。</p>
     */
    Long viewerUserId(BotIdentity identity) {
        if (identity.admin() || !identity.hasEmail()) {
            return null;
        }
        return userCache.userId(identity.email()).orElse(null);
    }

    /**
     * {@code /错误用户 <邮箱或ID> [时间窗]}。
     */
    private BotReport errorUserReport(String arg) {
        String[] parts = arg == null || arg.isBlank() ? new String[0] : arg.trim().split("\\s+", 2);
        if (parts.length == 0) {
            return text("用法：/错误用户 <邮箱或用户ID> [时间窗] [状态码|关键词]");
        }
        return errorService.userReport(parts[0], parts.length > 1 ? parts[1] : "");
    }

    /**
     * {@code /订单 <账号> [起始日期] [结束日期]}。
     *
     * <p>日期两段都可省；只给一段时它当起始日期，结束日期取今天。
     * 账号本身不含空格（邮箱 / 用户名 / 数字 ID），所以按空白切词足够。</p>
     */
    private BotReport orderReport(String arg) {
        String[] parts = arg == null || arg.isBlank() ? new String[0] : arg.trim().split("\\s+");
        if (parts.length == 0) {
            return text("用法：/订单 <邮箱或用户名> [起始日期] [结束日期]，例如 "
                    + "/订单 user@example.com 2026-09-01 2026-09-30；日期可省略，默认近 30 天。");
        }
        String from = parts.length > 1 ? parts[1] : "";
        String to = parts.length > 2 ? parts[2] : "";
        return orderTools.ordersReport(parts[0], from, to, BotOrderTools.DEFAULT_LIMIT);
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

    /**
     * {@code /画图 <提示词>}：生成图片并塞进本次消息的待发图片里。
     *
     * <p>命令与模型工具共用同一条取数链路（{@link ImageGenService}），
     * 限频也在那里统一做，所以「敲命令」和「说人话」走的是同一个闸门。</p>
     */
    private BotReport draw(String prompt, GeneratedImages generated, Runnable imageStartNotice) {
        ImageGenService.Result result = imageGenService.generate(prompt, imageStartNotice);
        if (!result.ok()) {
            return text(result.error());
        }
        generated.add(result.image());
        return text("画好了。");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
