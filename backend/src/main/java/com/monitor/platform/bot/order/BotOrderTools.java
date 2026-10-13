package com.monitor.platform.bot.order;

import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理端：查询 Sub2API 平台用户的订单与余额。
 *
 * <p>数据来自直连只读库（见 {@link Sub2PaymentOrderReader}）——上游 admin 接口不支持按日期范围过滤，
 * 只有直连库才能在 {@code user_id + created_at} 上走索引。</p>
 *
 * <p>两类入口共用同一套取数与渲染：{@code @Tool} 方法给大模型 function calling 用，
 * {@code *Report()} 方法给确定性命令（{@code /订单}）用，长内容会渲染成表格图片。</p>
 */
@Component
public class BotOrderTools {

    private static final Logger log = LoggerFactory.getLogger(BotOrderTools.class);

    /** 业务时区，与只读库读取侧保持一致。 */
    private static final ZoneId ZONE = BusinessTime.ZONE;

    /** 默认时间窗：近 30 天。 */
    private static final int DEFAULT_DAYS = 30;

    /** 单次查询最长跨度，避免一条命令把整张表扫一遍。 */
    private static final int MAX_DAYS = 366;

    /** 明细默认条数。 */
    public static final int DEFAULT_LIMIT = 20;

    /** 明细条数上限。 */
    public static final int MAX_LIMIT = 100;

    /** 「近 N 天」写法：{@code 7d} / {@code 7天} / {@code 近7天}。 */
    private static final Pattern LAST_DAYS = Pattern.compile("^(?:近)?(\\d{1,3})\\s*(?:[dD]|天)$");

    private static final DateTimeFormatter SHOW_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final String NO_DATABASE = """
            没有配置 Sub2API 只读库连接，订单查询不可用。
            需要设置 SUB2API_DB_URL 与 SUB2API_DB_USERNAME（和号池增量采集共用同一套连接信息）。""";

    private static final String USAGE = "用法：/订单 <邮箱或用户名> [起始日期] [结束日期]，"
            + "例如 /订单 user@example.com 2026-09-01 2026-09-30；日期可省略，默认近 30 天，也可写「近7天」。";

    private final Sub2PaymentOrderReader reader;

    public BotOrderTools(Sub2PaymentOrderReader reader) {
        this.reader = reader;
    }

    // ============================================================
    // 大模型工具：返回纯文本
    // ============================================================

    @Tool(description = "按邮箱或用户名查询某个 Sub2API 平台用户的支付订单：订单数、到账与实付合计，"
            + "以及订单明细（创建时间、类型、支付方式、金额、状态、支付时间）。"
            + "fromDate / toDate 是可选日期，格式 2026-09-01，留空表示默认近 30 天。")
    public String ordersByAccount(String account, String fromDate, String toDate) {
        return ordersReport(account, fromDate, toDate, DEFAULT_LIMIT).toPlainText();
    }

    @Tool(description = "按邮箱或用户名查询某个 Sub2API 平台用户的余额、冻结余额与累计充值。")
    public String accountBalanceByAccount(String account) {
        return userBalanceReport(account).toPlainText();
    }

    // ============================================================
    // 结构化报表：给确定性命令用
    // ============================================================

    /** 指定平台用户在时间窗内的订单：余额 + 汇总 + 明细。 */
    public BotReport ordersReport(String account, String fromDate, String toDate, int limit) {
        Lookup found = lookup(account);
        if (!found.ok()) {
            return found.failure();
        }
        var user = found.user();

        LocalDate today = LocalDate.now(ZONE);
        Range range = resolveRange(fromDate, toDate, today);
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        OffsetDateTime start = range.from().atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime end = range.to().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        try {
            var summary = reader.summarise(user.id(), start, end);
            List<Sub2PaymentOrder> orders = reader.listOrders(user.id(), start, end, size + 1);
            boolean truncated = orders.size() > size;
            if (truncated) {
                orders = orders.subList(0, size);
            }

            List<BotReport.Block> blocks = new ArrayList<>();
            blocks.add(new BotReport.Block(null, List.of(
                    BotReport.Col.left("项目"),
                    BotReport.Col.left("值")), List.of(
                    BotReport.Row.of("时间范围", range.from().format(SHOW_DATE) + " ~ " + range.to().format(SHOW_DATE)),
                    BotReport.Row.of("订单数", String.valueOf(summary.count())),
                    BotReport.Row.of("到账合计", money(summary.amount())),
                    BotReport.Row.of("实付合计", money(summary.payAmount())),
                    BotReport.Row.of("退款合计", money(summary.refundAmount()))), null));

            if (orders.isEmpty()) {
                blocks.add(BotReport.paragraph("订单明细", List.of("这个时间窗内没有订单。")));
            } else {
                List<BotReport.Row> rows = new ArrayList<>();
                for (Sub2PaymentOrder order : orders) {
                    rows.add(BotReport.Row.of(
                            time(order.createdAt()),
                            orderTypeLabel(order.orderType()),
                            paymentTypeLabel(order.paymentType()),
                            money(order.amount()),
                            money(order.payAmount()),
                            statusLabel(order.status()),
                            time(order.paidAt())));
                }
                blocks.add(new BotReport.Block("订单明细（最近 " + rows.size() + " 笔）", List.of(
                        BotReport.Col.left("创建时间"),
                        BotReport.Col.left("类型"),
                        BotReport.Col.left("支付方式"),
                        BotReport.Col.right("到账"),
                        BotReport.Col.right("实付"),
                        BotReport.Col.left("状态"),
                        BotReport.Col.left("支付时间")), rows, null));
            }

            List<String> notes = new ArrayList<>();
            notes.add("余额 " + money(user.balance())
                    + "（冻结 " + money(user.frozenBalance()) + "），累计充值 " + money(user.totalRecharged()) + "。");
            if (truncated) {
                notes.add("这个时间窗内还有更多订单，这里只列出最近 " + size + " 笔（汇总仍是全量）。");
            }
            return BotReport.of("订单查询：" + display(user), blocks, notes);
        } catch (RuntimeException ex) {
            return databaseFailure("查询订单", ex);
        }
    }

    /** 指定平台用户的余额。 */
    public BotReport userBalanceReport(String account) {
        Lookup found = lookup(account);
        if (!found.ok()) {
            return found.failure();
        }
        var user = found.user();
        List<BotReport.Row> rows = List.of(
                BotReport.Row.of("ID", String.valueOf(user.id())),
                BotReport.Row.of("邮箱", safe(user.email())),
                BotReport.Row.of("用户名", safe(user.username())),
                BotReport.Row.of("角色", safe(user.role())),
                BotReport.Row.of("状态", safe(user.status())),
                BotReport.Row.of("余额", money(user.balance())),
                BotReport.Row.of("冻结余额", money(user.frozenBalance())),
                BotReport.Row.of("累计充值", money(user.totalRecharged())));
        return BotReport.of("平台用户余额", List.of(
                new BotReport.Block(null, List.of(
                        BotReport.Col.left("项目"),
                        BotReport.Col.left("值")), rows, null)), List.of());
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 解析结果：要么拿到唯一用户，要么带着一份「该怎么回」的报表。 */
    private record Lookup(Sub2PaymentOrderReader.BillingUser user, BotReport failure) {

        boolean ok() {
            return user != null;
        }
    }

    /** 时间窗。 */
    private record Range(LocalDate from, LocalDate to) {
    }

    private Lookup lookup(String account) {
        if (!reader.isAvailable()) {
            return new Lookup(null, BotReport.fromMarkdown(NO_DATABASE));
        }
        String keyword = account == null ? "" : account.trim();
        if (keyword.isEmpty()) {
            return new Lookup(null, BotReport.fromMarkdown(USAGE));
        }
        List<Sub2PaymentOrderReader.BillingUser> candidates;
        try {
            candidates = reader.findUsers(keyword);
        } catch (RuntimeException ex) {
            return new Lookup(null, databaseFailure("查询平台用户", ex));
        }
        if (candidates.isEmpty()) {
            return new Lookup(null, BotReport.fromMarkdown("没有找到匹配「" + keyword + "」的平台用户。"));
        }
        Sub2PaymentOrderReader.BillingUser exact = pick(candidates, keyword);
        return exact != null
                ? new Lookup(exact, null)
                : new Lookup(null, candidateReport(keyword, candidates));
    }

    /** 邮箱或用户名精确命中的优先；否则只在唯一候选时才认定为目标。 */
    private static Sub2PaymentOrderReader.BillingUser pick(
            List<Sub2PaymentOrderReader.BillingUser> candidates, String keyword) {
        for (Sub2PaymentOrderReader.BillingUser user : candidates) {
            if (keyword.equalsIgnoreCase(user.email()) || keyword.equalsIgnoreCase(user.username())) {
                return user;
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static BotReport candidateReport(String keyword, List<Sub2PaymentOrderReader.BillingUser> candidates) {
        List<BotReport.Row> rows = new ArrayList<>();
        for (Sub2PaymentOrderReader.BillingUser user : candidates) {
            rows.add(BotReport.Row.of(
                    String.valueOf(user.id()),
                    safe(user.email()),
                    safe(user.username()),
                    safe(user.role())));
        }
        return BotReport.of("「" + keyword + "」匹配到多个平台用户", List.of(
                new BotReport.Block("请用完整邮箱或数字 ID 再查一次", List.of(
                        BotReport.Col.left("ID"),
                        BotReport.Col.left("邮箱"),
                        BotReport.Col.left("用户名"),
                        BotReport.Col.left("角色")), rows, null)), List.of());
    }

    /** 解析时间窗：支持省略、单边日期与非法的「近 N 天」。 */
    private Range resolveRange(String fromRaw, String toRaw, LocalDate today) {
        LocalDate to = parseDate(toRaw, today);
        String fromText = fromRaw == null ? "" : fromRaw.trim();
        Matcher last = LAST_DAYS.matcher(fromText);
        LocalDate from;
        if (last.matches()) {
            int days = Math.min(Integer.parseInt(last.group(1)), MAX_DAYS);
            from = to.minusDays(Math.max(0, days - 1L));
        } else {
            from = parseDate(fromText, to.minusDays(DEFAULT_DAYS - 1L));
        }
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            from = to.minusDays(MAX_DAYS);
        }
        return new Range(from, to);
    }

    /** 认 {@code 2026-09-01} / {@code 2026/9/1} / {@code 9-1}；认不出就退回默认值。 */
    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().replace('/', '-').replace('.', '-');
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu-M-d"));
        } catch (DateTimeParseException ignored) {
            // 再试不带年份的写法
        }
        try {
            return LocalDate.parse(fallback.getYear() + "-" + value, DateTimeFormatter.ofPattern("uuuu-M-d"));
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static BotReport databaseFailure(String action, RuntimeException ex) {
        log.warn("{}失败: {}", action, ex.getMessage());
        return BotReport.fromMarkdown(action + "失败：" + rootMessage(ex)
                + "\n如果提示权限不足，需要在 Sub2API 库执行 GRANT SELECT ON public.payment_orders TO monitor_ro;");
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String display(Sub2PaymentOrderReader.BillingUser user) {
        if (user.email() != null && !user.email().isBlank()) {
            return user.email();
        }
        return user.username() == null || user.username().isBlank() ? ("用户 " + user.id()) : user.username();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String money(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "-" : SHOW_TIME.format(value.atZoneSameInstant(ZONE));
    }

    /** 订单类型：Sub2API 只有 balance / subscription 两种。 */
    private static String orderTypeLabel(String orderType) {
        if (orderType == null || orderType.isBlank()) {
            return "-";
        }
        return switch (orderType.toLowerCase(java.util.Locale.ROOT)) {
            case "balance" -> "余额充值";
            case "subscription" -> "订阅";
            default -> orderType;
        };
    }

    /** 支付方式：枚举见 Sub2API 的 payment.Type*。 */
    private static String paymentTypeLabel(String paymentType) {
        if (paymentType == null || paymentType.isBlank()) {
            return "-";
        }
        return switch (paymentType.toLowerCase(java.util.Locale.ROOT)) {
            case "alipay" -> "支付宝";
            case "alipay_direct" -> "支付宝直连";
            case "wxpay" -> "微信支付";
            case "wxpay_direct" -> "微信直连";
            case "stripe" -> "Stripe";
            case "card" -> "银行卡";
            case "link" -> "Link";
            case "easypay" -> "易支付";
            case "airwallex" -> "Airwallex";
            default -> paymentType;
        };
    }

    /** 订单状态：枚举见 Sub2API 的 payment.OrderStatus*。 */
    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "-";
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "pending" -> "待支付";
            case "paid" -> "已支付";
            case "recharging" -> "充值中";
            case "completed" -> "已完成";
            case "expired" -> "已过期";
            case "cancelled" -> "已取消";
            case "failed" -> "失败";
            case "refund_requested" -> "退款申请中";
            case "refunding" -> "退款中";
            case "refund_pending" -> "退款待处理";
            case "partially_refunded" -> "部分退款";
            case "refunded" -> "已退款";
            case "refund_failed" -> "退款失败";
            default -> status;
        };
    }
}
