package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 上游渠道倍率变化 → 我平台影响评估。
 *
 * <p>数据全部来自项目自己采集的 <b>upstream_groups</b>（对应 Sub2API 的
 * {@code GET /api/v1/groups/available}）：<strong>一个账号可以同时属于多个分组</strong>，
 * 每个分组都带自己的 {@code rate_multiplier}（入库为 {@code current_ratio}）。</p>
 *
 * <p>评估口径：上游渠道涨价后，拿新倍率和<strong>该账号所在的所有分组倍率</strong>逐个比 ——
 * 只要新倍率大于某个分组的倍率，就说明按那个分组卖已经不划算（本类把它标为「倒挂」）。</p>
 *
 * <p>提醒级别：降价 → INFO；涨价但未超过任何分组 → WARNING；
 * 已倒挂 → WARNING（涨幅不足阈值）/ CRITICAL（涨幅达到阈值）。</p>
 */
@Service
public class RateChangeImpactService {

    /** 涨幅达到这个百分比时，受影响分组的提醒级别升到 CRITICAL。 */
    private static final BigDecimal CRITICAL_INCREASE_PERCENT = new BigDecimal("50");

    private final UpstreamGroupRepository groupRepository;
    private final AccountRepository accountRepository;

    public RateChangeImpactService(UpstreamGroupRepository groupRepository,
                                   AccountRepository accountRepository) {
        this.groupRepository = groupRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * 一行 = 一个（账号 × 所属分组）。
     *
     * @param accountName     账号名（我平台账号）
     * @param accountId       账号主键
     * @param groupName       所属分组名
     * @param groupRatio      该分组的倍率
     * @param upstreamChannel 发生变化的那个上游渠道名
     * @param oldRatio        上游渠道原倍率
     * @param newRatio        上游渠道新倍率
     * @param increasePercent 涨价幅度（%，涨为正值）
     * @param affected        上游新倍率是否已超过该分组倍率
     * @param level           提醒级别：INFO / WARNING / CRITICAL
     */
    public record ImpactRow(String accountName, Integer accountId, String groupName, BigDecimal groupRatio,
                            String upstreamChannel, BigDecimal oldRatio, BigDecimal newRatio,
                            BigDecimal increasePercent, boolean affected, String level) {
    }

    /** 这条变更的影响评估；不是渠道倍率事件时返回空列表。 */
    public List<ImpactRow> impactOf(UpstreamChangeEventEntity event) {
        if (event == null) {
            return List.of();
        }
        return impactsOf(List.of(event)).getOrDefault(event.getId(), List.of());
    }

    /**
     * 批量影响评估：账号名与分组各查一次，避免变更列表（最多几百条）逐条打库。
     *
     * @return eventId → 影响行；只包含渠道倍率事件，其它事件不在返回结果里
     */
    public Map<Long, List<ImpactRow>> impactsOf(List<UpstreamChangeEventEntity> events) {
        if (events == null || events.isEmpty()) {
            return Map.of();
        }
        List<UpstreamChangeEventEntity> rateEvents = events.stream()
                .filter(RateChangeImpactService::isRateChange)
                .toList();
        if (rateEvents.isEmpty()) {
            return Map.of();
        }

        Map<Integer, String> accountNames = rateEvents.stream()
                .map(UpstreamChangeEventEntity::getAccountId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::accountName, (a, b) -> a, HashMap::new));
        Map<Integer, List<UpstreamGroupEntity>> groupsByAccount = rateEvents.stream()
                .map(UpstreamChangeEventEntity::getAccountId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(),
                        accountId -> groupRepository.findByAccount(accountId, true),
                        (a, b) -> a, HashMap::new));

        Map<Long, List<ImpactRow>> result = new LinkedHashMap<>();
        for (UpstreamChangeEventEntity event : rateEvents) {
            result.put(event.getId(), buildRows(event,
                    accountNames.get(event.getAccountId()),
                    groupsByAccount.getOrDefault(event.getAccountId(), List.of())));
        }
        return result;
    }

    private static boolean isRateChange(UpstreamChangeEventEntity event) {
        if (event == null || !"GROUP".equalsIgnoreCase(event.getEntityType()) || event.getChangeType() == null) {
            return false;
        }
        return "RATE_CHANGED".equalsIgnoreCase(event.getChangeType())
                || "BASE_RATE_CHANGED".equalsIgnoreCase(event.getChangeType());
    }

    private List<ImpactRow> buildRows(UpstreamChangeEventEntity event, String accountName,
                                      List<UpstreamGroupEntity> groups) {
        BigDecimal before = decimal(event.getOldValue());
        BigDecimal after = decimal(event.getNewValue());
        if (after == null || groups.isEmpty()) {
            return List.of();
        }
        String upstreamChannel = event.getGroupName() == null || event.getGroupName().isBlank()
                ? event.getEntityKey()
                : event.getGroupName();
        boolean rising = before != null && after.compareTo(before) > 0;
        BigDecimal increase = percentIncrease(before, after);

        List<ImpactRow> rows = new ArrayList<>();
        for (UpstreamGroupEntity group : groups) {
            boolean affected = rising && group.getCurrentRatio() != null
                    && after.compareTo(group.getCurrentRatio()) > 0;
            rows.add(new ImpactRow(
                    accountName,
                    event.getAccountId(),
                    group.getGroupName() == null || group.getGroupName().isBlank()
                            ? group.getExternalGroupId()
                            : group.getGroupName(),
                    group.getCurrentRatio(),
                    upstreamChannel,
                    before,
                    after,
                    increase,
                    affected,
                    levelOf(rising, affected, increase)));
        }
        // 受影响的排前面、倍率高的排前面：第一眼要看到的是「哪些分组已经倒挂」
        rows.sort(Comparator.comparing(ImpactRow::affected).reversed()
                .thenComparing(ImpactRow::groupRatio, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /** 上游渠道名，仅用于日志/展示的兜底。 */
    static String channelOf(UpstreamChangeEventEntity event) {
        return event.getGroupName() == null || event.getGroupName().isBlank()
                ? event.getEntityKey()
                : event.getGroupName();
    }

    /** 是否有分组「倒挂」（上游成本已高于分组售价）。 */
    public static boolean hasAffected(List<ImpactRow> rows) {
        return rows != null && rows.stream().anyMatch(ImpactRow::affected);
    }

    /** 整批事件里受影响最严重的级别，用于邮件主题与提醒级别标注。 */
    public static String overallLevel(List<ImpactRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "INFO";
        }
        if (rows.stream().anyMatch(row -> "CRITICAL".equals(row.level()) && row.affected())) {
            return "CRITICAL";
        }
        if (rows.stream().anyMatch(ImpactRow::affected)) {
            return "WARNING";
        }
        return rows.stream().anyMatch(row -> "WARNING".equals(row.level())) ? "WARNING" : "INFO";
    }

    private static String levelOf(boolean rising, boolean affected, BigDecimal increase) {
        if (!rising) {
            return "INFO";
        }
        if (!affected) {
            return "WARNING";
        }
        return increase != null && increase.compareTo(CRITICAL_INCREASE_PERCENT) >= 0 ? "CRITICAL" : "WARNING";
    }

    /** 上游原/新倍率的涨幅百分比；原倍率缺失或为 0 时返回 null。 */
    static BigDecimal percentIncrease(BigDecimal before, BigDecimal after) {
        if (after == null || before == null || before.signum() == 0) {
            return null;
        }
        return after.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 2, RoundingMode.HALF_UP);
    }

    /** JSON 文本形式的倍率（可能是 1.2 或 "1.2"）转成数值。 */
    static BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().replaceAll("^\"|\"$", "");
        if (text.isEmpty() || text.startsWith("{") || text.startsWith("[")) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String accountName(Integer accountId) {
        if (accountId == null) {
            return "未知账号";
        }
        AccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return "账号 " + accountId;
        }
        if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
            return account.getDisplayName();
        }
        if (account.getUsername() != null && !account.getUsername().isBlank()) {
            return account.getUsername();
        }
        return "账号 " + accountId;
    }
}