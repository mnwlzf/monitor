package com.monitor.platform.bot.order;

import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 直连 Sub2API 只读库读取「平台用户的支付订单与余额」。
 *
 * <p>为什么不用上游 HTTP 接口：{@code GET /api/v1/admin/payment/orders} 只支持
 * {@code user_id / status / order_type / payment_type / keyword} 加翻页，
 * <strong>没有日期范围参数</strong>；而 {@code payment_orders} 上有
 * {@code idx_payment_orders_user_id} 与 {@code idx_payment_orders_created_at}，
 * 直连库按 {@code user_id + created_at} 过滤才走得上索引。</p>
 *
 * <p>复用 {@code monitor.pool.ingest.*} 里已经配好的只读连接信息（与号池增量采集同一个库），
 * 但不依赖 {@code enabled} 开关：关掉增量采集时，订单查询仍然可用。
 * 连接池统一由 {@link Sub2ReadOnlyJdbc} 提供，这里只负责取数。</p>
 *
 * <p>需要上游给只读账号授权，否则查询会以权限不足失败：
 * {@code GRANT SELECT ON public.payment_orders TO monitor_ro;}（{@code public.users} 同理由身份识别使用）。</p>
 */
@Component
public class Sub2PaymentOrderReader {

    /** 业务时区：无时区的 timestamp 按此时区解释。 */
    private static final ZoneId ZONE = BusinessTime.ZONE;

    /**
     * 按邮箱 / 用户名 / 数字 id 解析平台用户。
     *
     * <p>{@code deleted_at} 是软删除标记，必须过滤。命中优先级用 {@code ORDER BY} 表达：
     * 邮箱精确匹配 > 用户名精确匹配 > 数字 id，这样调用方取第一条即可。</p>
     */
    private static final String SELECT_USER = """
            SELECT id, email, username, role, status, balance, frozen_balance, total_recharged
            FROM public.users
            WHERE deleted_at IS NULL
              AND (lower(email) = lower(?) OR lower(username) = lower(?) OR id::text = ?)
            ORDER BY CASE
                       WHEN lower(email) = lower(?) THEN 0
                       WHEN lower(username) = lower(?) THEN 1
                       ELSE 2
                     END,
                     id
            LIMIT 5
            """;

    /**
     * 指定用户、指定时间窗的订单。
     *
     * <p>只取 092 建表就存在的列，理由见 {@link Sub2PaymentOrder}。
     * 时间窗用左闭右开 {@code >= from AND < to}，避免 {@code BETWEEN} 在整点边界上重复计入。</p>
     */
    private static final String SELECT_ORDERS = """
            SELECT id, user_id, user_email, user_name, amount, pay_amount,
                   order_type, payment_type, status, refund_amount,
                   created_at, paid_at, completed_at, failed_at, failed_reason
            FROM public.payment_orders
            WHERE user_id = ?
              AND created_at >= ?
              AND created_at < ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;

    /** 同一时间窗的汇总，与明细分开查：明细被 LIMIT 截断时汇总仍然完整。 */
    private static final String SELECT_SUMMARY = """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(amount), 0) AS amount_sum,
                   COALESCE(SUM(pay_amount), 0) AS pay_sum,
                   COALESCE(SUM(refund_amount), 0) AS refund_sum
            FROM public.payment_orders
            WHERE user_id = ?
              AND created_at >= ?
              AND created_at < ?
            """;

    private static final RowMapper<BillingUser> USER_MAPPER = (rs, rowNum) -> new BillingUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("status"),
            rs.getBigDecimal("balance"),
            rs.getBigDecimal("frozen_balance"),
            rs.getBigDecimal("total_recharged")
    );

    private static final RowMapper<Sub2PaymentOrder> ORDER_MAPPER = (rs, rowNum) -> new Sub2PaymentOrder(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("user_email"),
            rs.getString("user_name"),
            rs.getBigDecimal("amount"),
            rs.getBigDecimal("pay_amount"),
            rs.getString("order_type"),
            rs.getString("payment_type"),
            rs.getString("status"),
            rs.getBigDecimal("refund_amount"),
            offsetDateTimeOrNull(rs, "created_at"),
            offsetDateTimeOrNull(rs, "paid_at"),
            offsetDateTimeOrNull(rs, "completed_at"),
            offsetDateTimeOrNull(rs, "failed_at"),
            rs.getString("failed_reason")
    );

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2PaymentOrderReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 是否配置了可用的只读库连接（不看增量采集开关）。 */
    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 按邮箱 / 用户名 / 数字 id 解析平台用户，最多 5 个候选，命中优先级高的排前面。 */
    public List<BillingUser> findUsers(String account) {
        return jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_USER);
            for (int index = 1; index <= 5; index++) {
                statement.setString(index, account);
            }
            return statement;
        }, USER_MAPPER);
    }

    /** 指定用户、指定时间窗的订单（按创建时间倒序，最多 {@code limit} 条）。 */
    public List<Sub2PaymentOrder> listOrders(long userId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_ORDERS);
            statement.setLong(1, userId);
            statement.setObject(2, from);
            statement.setObject(3, to);
            statement.setInt(4, limit);
            return statement;
        }, ORDER_MAPPER);
    }

    /** 指定用户、指定时间窗的订单汇总（不受明细条数上限影响）。 */
    public OrderSummary summarise(long userId, OffsetDateTime from, OffsetDateTime to) {
        List<OrderSummary> rows = jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_SUMMARY);
            statement.setLong(1, userId);
            statement.setObject(2, from);
            statement.setObject(3, to);
            return statement;
        }, (rs, rowNum) -> new OrderSummary(
                rs.getLong("order_count"),
                rs.getBigDecimal("amount_sum"),
                rs.getBigDecimal("pay_sum"),
                rs.getBigDecimal("refund_sum")
        ));
        return rows.isEmpty()
                ? new OrderSummary(0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : rows.get(0);
    }

    /**
     * {@code created_at} 可能是 timestamptz 也可能是 timestamp，两种都收。
     *
     * <p>与 {@code Sub2ApiUsageLogReader} 同样的取舍：先试 {@code getObject(.., OffsetDateTime.class)}，
     * 无时区的列走不通再按业务时区解释。</p>
     */
    private static OffsetDateTime offsetDateTimeOrNull(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getObject(column, OffsetDateTime.class);
        } catch (SQLException ignored) {
            // 无时区的 timestamp 走不通上面的转换，退回按业务时区解释
        }
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZONE).toOffsetDateTime();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(ZONE).toOffsetDateTime();
        }
        return null;
    }

    /**
     * 平台用户（含余额）。
     *
     * @param id            用户主键
     * @param email         邮箱
     * @param username      用户名
     * @param role          角色
     * @param status        账号状态
     * @param balance       余额
     * @param frozenBalance 冻结余额
     * @param totalRecharged 累计充值
     */
    public record BillingUser(
            long id,
            String email,
            String username,
            String role,
            String status,
            BigDecimal balance,
            BigDecimal frozenBalance,
            BigDecimal totalRecharged
    ) {
    }

    /**
     * 时间窗内的订单汇总。
     *
     * @param count        订单数
     * @param amount       到账金额合计
     * @param payAmount    实付金额合计
     * @param refundAmount 退款金额合计
     */
    public record OrderSummary(long count, BigDecimal amount, BigDecimal payAmount, BigDecimal refundAmount) {
    }
}
