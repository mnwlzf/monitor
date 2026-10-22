package com.monitor.platform.bot.user;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员余额调整服务。
 *
 * <p>只允许增加余额，并强制两步确认：第一步解析用户、展示当前余额并生成一次性确认码；
 * 用户查询始终按模糊匹配返回候选；管理员选定候选用户后，再由同一管理员在后续消息中明确回复「确认充值 &lt;确认码&gt;」后才调用 Sub2API。</p>
 *
 * <p>确认码按管理员邮箱隔离，且带过期时间。真正调用 Sub2API 时携带固定幂等键，
 * 即使网络超时后重试同一确认码，也不会重复加余额。</p>
 */
@Service
public class BotBalanceService {

    private static final Logger log = LoggerFactory.getLogger(BotBalanceService.class);

    private static final int SEARCH_PAGE_SIZE = 20;
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_REASON_LENGTH = 200;
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final Duration COMPLETED_RETENTION = Duration.ofMinutes(30);

    private final PlatformRepository platformRepository;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final Sub2UserAdminClient client;
    private final Map<String, PendingChange> pending = new ConcurrentHashMap<>();

    public BotBalanceService(PlatformRepository platformRepository,
                             PoolAdminKeyResolver adminKeyResolver,
                             Sub2UserAdminClient client) {
        this.platformRepository = platformRepository;
        this.adminKeyResolver = adminKeyResolver;
        this.client = client;
    }

    /** 所有用户查询都按模糊匹配返回候选，不自动选定具体人员。 */
    public String search(String query) {
        if (isBlank(query)) {
            return "请输入用户 ID、邮箱或用户名。";
        }
        Connection connection = connection();
        if (connection.error() != null) {
            return connection.error();
        }
        return candidatesText(fuzzyCandidates(connection, query), "匹配到候选用户：");
    }

    /** 查询充值候选；校验金额但不创建确认码，等管理员选定具体人员。 */
    public String candidatesForRecharge(String query, Double amountUsd) {
        if (isBlank(query)) {
            return "请输入用户 ID、邮箱或用户名。";
        }
        BigDecimal amount;
        try {
            amount = normalizeAmount(amountUsd);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }

        Connection connection = connection();
        if (connection.error() != null) {
            return connection.error();
        }
        List<Sub2AdminUser> candidates = fuzzyCandidates(connection, query);
        return candidatesText(candidates, "充值金额：$" + money(amount) + "\n请确认具体人员：");
    }

    /** 管理员从候选列表选定具体用户后，生成充值预览。 */
    public String previewForUser(long userId, Double amountUsd, String reason, String operatorEmail) {
        if (isBlank(operatorEmail)) {
            return "无法识别当前管理员邮箱，不能执行余额调整。";
        }
        BigDecimal amount;
        try {
            amount = normalizeAmount(amountUsd);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }

        Connection connection = connection();
        if (connection.error() != null) {
            return connection.error();
        }
        Optional<Sub2AdminUser> user = client.findUserById(
                connection.baseUrl(), connection.adminKey(), userId);
        if (user.isEmpty()) {
            return "没有找到 ID 为 " + userId + " 的平台用户。";
        }

        Sub2AdminUser selected = user.get();
        String code = newCode();
        Instant now = Instant.now();
        PendingChange change = new PendingChange(
                code, selected.id(), operatorEmail.trim(), amount, normalizeReason(reason),
                now.plus(CONFIRMATION_TTL));
        pending.put(code, change);
        return formatPreview(selected, change);
    }

    /**
     * 执行已经确认的充值。
     *
     * <p>调用方必须先通过 {@link BotBalanceTools} 校验当前用户消息里确实包含
     * 「确认充值 + 确认码」，这里再校验确认码归属、有效期和单次使用。</p>
     */
    public String confirm(String code, String operatorEmail) {
        cleanup();
        if (isBlank(code)) {
            return "缺少确认码，请使用「确认充值 <确认码>」。";
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        PendingChange change = pending.get(normalizedCode);
        if (change == null) {
            return "确认码不存在或已过期，请重新发起充值预览。";
        }

        synchronized (change) {
            if (!change.operatorEmail.equalsIgnoreCase(operatorEmail)) {
                return "这个确认码不属于当前管理员，不能执行。";
            }
            if (change.status == Status.COMPLETED) {
                return change.result;
            }
            if (change.status == Status.EXPIRED || Instant.now().isAfter(change.expiresAt)) {
                change.status = Status.EXPIRED;
                return "确认码已过期，请重新发起充值预览。";
            }
            if (change.status == Status.CONFIRMING) {
                return "这笔充值正在处理中，请稍后查看结果，不要重复确认。";
            }

            change.status = Status.CONFIRMING;
            try {
                Connection connection = connection();
                if (connection.error() != null) {
                    change.status = Status.PENDING;
                    return "充值失败：" + connection.error();
                }
                Optional<Sub2AdminUser> before = client.findUserById(
                        connection.baseUrl(), connection.adminKey(), change.userId);
                if (before.isEmpty()) {
                    change.status = Status.PENDING;
                    return "充值失败：平台用户不存在，可能已被删除。";
                }

                Sub2AdminUser updated = client.updateBalance(
                        connection.baseUrl(),
                        connection.adminKey(),
                        change.userId,
                        change.amount,
                        notes(change),
                        idempotencyKey(change.code));
                if (updated == null) {
                    updated = client.findUserById(connection.baseUrl(), connection.adminKey(), change.userId)
                            .orElse(before.get());
                }

                change.status = Status.COMPLETED;
                change.completedAt = Instant.now();
                change.result = formatSuccess(before.get(), updated, change);
                log.info("管理员余额增加成功: operator={}, userId={}, amount={}, code={}",
                        change.operatorEmail, change.userId, change.amount.toPlainString(), change.code);
                return change.result;
            } catch (RuntimeException ex) {
                // 保留 PENDING，允许用同一确认码和同一幂等键重试。
                change.status = Status.PENDING;
                String reason = safeMessage(ex);
                log.warn("管理员余额增加失败: operator={}, userId={}, amount={}, reason={}",
                        change.operatorEmail, change.userId, change.amount.toPlainString(), reason);
                return "充值失败：" + reason;
            }
        }
    }

    private List<Sub2AdminUser> fuzzyCandidates(Connection connection, String query) {
        String keyword = query.trim();
        List<Sub2AdminUser> candidates = new ArrayList<>(client.searchUsers(
                connection.baseUrl(), connection.adminKey(), keyword, SEARCH_PAGE_SIZE));

        // 数字 ID 也作为候选加入，但不会自动选中，仍需管理员确认具体人员。
        if (keyword.matches("\\d+")) {
            try {
                long userId = Long.parseLong(keyword);
                if (userId > 0) {
                    client.findUserById(connection.baseUrl(), connection.adminKey(), userId)
                            .filter(user -> candidates.stream().noneMatch(item ->
                                    item.id() != null && item.id().equals(user.id())))
                            .ifPresent(user -> candidates.add(0, user));
                }
            } catch (NumberFormatException ignored) {
                // 超出 long 范围的数字仍按普通关键字搜索
            }
        }
        return candidates;
    }

    private Connection connection() {
        PlatformEntity platform = platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .findFirst()
                .orElse(null);
        if (platform == null) {
            return Connection.error("平台还没配置好（未标记号池监控源），请联系管理员。");
        }
        String adminKey = adminKeyResolver.resolve(platform).orElse(null);
        if (adminKey == null) {
            return Connection.error("平台管理员密钥未配置，请联系管理员。");
        }
        return new Connection(platform.getUrl(), adminKey, null);
    }

    private String formatPreview(Sub2AdminUser user, PendingChange change) {
        BigDecimal current = BigDecimal.valueOf(user.balance() == null ? 0d : user.balance());
        BigDecimal after = current.add(change.amount);
        StringBuilder text = new StringBuilder("充值预览（仅增加余额）\n\n");
        text.append("用户：").append(userLabel(user)).append('\n');
        text.append("当前余额：$").append(money(current)).append('\n');
        text.append("本次增加：$").append(money(change.amount)).append('\n');
        text.append("预计余额：$").append(money(after)).append('\n');
        text.append("累计充值：$").append(money(BigDecimal.valueOf(
                user.totalRecharged() == null ? 0d : user.totalRecharged()))).append('\n');
        if (!change.reason.isBlank()) {
            text.append("备注：").append(change.reason).append('\n');
        }
        text.append("\n确认码：").append(change.code)
                .append("（5 分钟内有效，只能使用一次）\n");
        text.append("二次确认请回复：确认充值 ").append(change.code);
        return text.toString();
    }

    private String formatSuccess(Sub2AdminUser before, Sub2AdminUser updated, PendingChange change) {
        BigDecimal oldBalance = BigDecimal.valueOf(before.balance() == null ? 0d : before.balance());
        BigDecimal newBalance = BigDecimal.valueOf(updated.balance() == null
                ? oldBalance.add(change.amount).doubleValue()
                : updated.balance());
        StringBuilder text = new StringBuilder("充值成功\n\n");
        text.append("用户：").append(userLabel(updated)).append('\n');
        text.append("充值前：$").append(money(oldBalance)).append('\n');
        text.append("本次增加：$").append(money(change.amount)).append('\n');
        text.append("充值后：$").append(money(newBalance)).append('\n');
        text.append("累计充值：$").append(money(BigDecimal.valueOf(
                updated.totalRecharged() == null ? 0d : updated.totalRecharged()))).append('\n');
        text.append("状态：").append(statusText(updated.status())).append('\n');
        text.append("确认码：").append(change.code);
        return text.toString();
    }

    private String candidatesText(List<Sub2AdminUser> candidates, String prefix) {
        if (candidates == null || candidates.isEmpty()) {
            return "没有找到匹配的用户。";
        }
        StringBuilder text = new StringBuilder(prefix == null ? "匹配到多个用户：" : prefix);
        text.append('\n');
        candidates.stream().limit(MAX_CANDIDATES).forEach(user -> text
                .append("- ID ").append(user.id())
                .append(" | 用户名 ").append(nz(user.username()))
                .append(" | 邮箱 ").append(nz(user.email()))
                .append(" | 余额 $").append(money(BigDecimal.valueOf(
                        user.balance() == null ? 0d : user.balance())))
                .append(" | 状态 ").append(statusText(user.status()))
                .append('\n'));
        if (candidates.size() > MAX_CANDIDATES) {
            text.append("还有 ").append(candidates.size() - MAX_CANDIDATES).append(" 个候选未显示。\n");
        }
        text.append("请确认具体人员，然后回复：选择用户 <ID>。");
        return text.toString();
    }

    private static BigDecimal normalizeAmount(Double amountUsd) {
        if (amountUsd == null) {
            throw new IllegalArgumentException("请提供充值金额（美元）。");
        }
        if (!Double.isFinite(amountUsd)) {
            throw new IllegalArgumentException("充值金额格式不正确。");
        }
        BigDecimal amount = BigDecimal.valueOf(amountUsd);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0。");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("充值金额最多保留两位小数。");
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String cleaned = reason.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= MAX_REASON_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_REASON_LENGTH);
    }

    private static String notes(PendingChange change) {
        return "QQ机器人充值 by " + change.operatorEmail
                + (change.reason.isBlank() ? "" : "：" + change.reason);
    }

    private static String idempotencyKey(String code) {
        return "monitor-bot-balance-" + code;
    }

    private void cleanup() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> {
            PendingChange change = entry.getValue();
            if (change.status == Status.COMPLETED) {
                return change.completedAt != null
                        && change.completedAt.plus(COMPLETED_RETENTION).isBefore(now);
            }
            return now.isAfter(change.expiresAt.plus(COMPLETED_RETENTION));
        });
    }

    private String newCode() {
        while (true) {
            String code = UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 8).toUpperCase(Locale.ROOT);
            if (!pending.containsKey(code)) {
                return code;
            }
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static String userLabel(Sub2AdminUser user) {
        return nz(user.username()) + " / " + nz(user.email()) + "（ID " + user.id() + "）";
    }

    private static String statusText(String status) {
        if (status == null || status.isBlank()) {
            return "—";
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "active" -> "正常";
            case "disabled", "banned" -> "已停用";
            case "expired" -> "已过期";
            case "exhausted" -> "额度用尽";
            default -> status;
        };
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private enum Status {
        PENDING,
        CONFIRMING,
        COMPLETED,
        EXPIRED
    }

    private static final class PendingChange {
        private final String code;
        private final long userId;
        private final String operatorEmail;
        private final BigDecimal amount;
        private final String reason;
        private final Instant expiresAt;
        private Status status = Status.PENDING;
        private Instant completedAt;
        private String result;

        private PendingChange(String code, long userId, String operatorEmail, BigDecimal amount,
                              String reason, Instant expiresAt) {
            this.code = code;
            this.userId = userId;
            this.operatorEmail = operatorEmail;
            this.amount = amount;
            this.reason = reason;
            this.expiresAt = expiresAt;
        }
    }

    private record Connection(String baseUrl, String adminKey, String error) {
        private static Connection error(String message) {
            return new Connection(null, null, message);
        }
    }

}
