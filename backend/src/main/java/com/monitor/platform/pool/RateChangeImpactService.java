package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 上游渠道倍率变化 → 本平台影响评估。
 *
 * <p>链路：上游渠道（upstream_groups）→ 该账号在该分组下的<strong>上游 Key</strong>
 * （account_api_keys）→ 绑定到的<strong>本平台号池账号</strong>
 * （pool_accounts.bound_key_id）→ 该账号最近<strong>服务过的本平台分组</strong>
 * （usage_logs.account_id + group_id）→ 分组倍率（groups.rate_multiplier）。</p>
 *
 * <p><strong>是否受影响</strong>：上游新倍率 <em>大于</em> 该分组倍率 ——
 * 也就是「按这个分组卖出去的单价，已经低于上游成本」。一个账号可能同时服务多个分组，
 * 因此结果是一行一个（账号 × 分组）。</p>
 *
 * <p>提醒级别：降价 → INFO；涨价但未超过任何分组 → WARNING；
 * 涨价且已超过分组 → WARNING（涨幅不足阈值）/ CRITICAL（涨幅达到阈值）。</p>
 */
@Service
public class RateChangeImpactService {

    /** 涨幅达到这个百分比时，受影响分组的提醒级别升到 CRITICAL。 */
    private static final BigDecimal CRITICAL_INCREASE_PERCENT = new BigDecimal("50");

    private final AccountApiKeyRepository apiKeyRepository;
    private final PoolAccountRepository poolAccountRepository;
    private final Sub2GroupRatioReader groupReader;

    public RateChangeImpactService(AccountApiKeyRepository apiKeyRepository,
                                   PoolAccountRepository poolAccountRepository,
                                   Sub2GroupRatioReader groupReader) {
        this.apiKeyRepository = apiKeyRepository;
        this.poolAccountRepository = poolAccountRepository;
        this.groupReader = groupReader;
    }

    /**
     * 一行 = 一个（本平台号池账号 × 所属分组）。
     *
     * @param platformAccount   本平台号池账号名
     * @param externalAccountId 本平台号池账号 ID（Sub2API account id）
     * @param groupName         本平台分组名
     * @param groupRatio        本平台分组倍率
     * @param upstreamChannel   上游渠道（分组）名
     * @param oldRatio          上游原倍率
     * @param newRatio          上游新倍率
     * @param increasePercent   涨价幅度（%，涨为正值）
     * @param affected          上游新倍率是否已超过该分组倍率
     * @param level             提醒级别：INFO / WARNING / CRITICAL
     */
    public record ImpactRow(String platformAccount, Long externalAccountId, String groupName,
                            BigDecimal groupRatio, String upstreamChannel,
                            BigDecimal oldRatio, BigDecimal newRatio, BigDecimal increasePercent,
                            boolean affected, String level) {
    }

    /** 这条变更的影响评估；不是渠道倍率事件 / 读不到只读库时返回空列表。 */
    public List<ImpactRow> impactOf(UpstreamChangeEventEntity event) {
        if (event == null) {
            return List.of();
        }
        return impactsOf(List.of(event)).getOrDefault(event.getId(), List.of());
    }

    /**
     * 批量影响评估：号池账号与分组各查一次，避免变更列表（最多几百条）逐条打库。
     *
     * @return eventId → 影响行；只包含渠道倍率事件，其它事件不在返回结果里
     */
    public Map<Long, List<ImpactRow>> impactsOf(List<UpstreamChangeEventEntity> events) {
        if (events == null || events.isEmpty() || !groupReader.isAvailable()) {
            return Map.of();
        }
        List<UpstreamChangeEventEntity> rateEvents = events.stream()
                .filter(RateChangeImpactService::isRateChange)
                .toList();
        if (rateEvents.isEmpty()) {
            return Map.of();
        }

        Map<Long, PoolAccountEntityView> poolByKey = boundPoolAccounts();
        Map<Integer, List<AccountApiKeyEntity>> keysByAccount = new HashMap<>();
        Map<Long, List<AccountApiKeyEntity>> scopedKeys = new HashMap<>();
        Set<Long> externalIds = new LinkedHashSet<>();
        for (UpstreamChangeEventEntity event : rateEvents) {
            List<AccountApiKeyEntity> keys = keysByAccount.computeIfAbsent(event.getAccountId(),
                    accountId -> apiKeyRepository.findByAccount(accountId, false));
            List<AccountApiKeyEntity> scoped = scopeKeys(keys, channelOf(event));
            scopedKeys.put(event.getId(), scoped);
            for (AccountApiKeyEntity key : scoped) {
                PoolAccountEntityView pool = poolByKey.get(key.getId());
                if (pool != null) {
                    externalIds.add(pool.externalAccountId());
                }
            }
        }
        Map<Long, List<Sub2GroupRatioReader.PlatformGroup>> groups = groupReader.groupsByAccount(externalIds);

        Map<Long, List<ImpactRow>> result = new LinkedHashMap<>();
        for (UpstreamChangeEventEntity event : rateEvents) {
            result.put(event.getId(),
                    buildRows(event, scopedKeys.getOrDefault(event.getId(), List.of()), poolByKey, groups));
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

    private static String channelOf(UpstreamChangeEventEntity event) {
        return event.getGroupName() == null || event.getGroupName().isBlank()
                ? event.getEntityKey()
                : event.getGroupName();
    }

    /** 优先只取「发生变化的那个分组」下的 Key；分组名对不上时退回该账号全部 Key。 */
    private static List<AccountApiKeyEntity> scopeKeys(List<AccountApiKeyEntity> keys, String channel) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<AccountApiKeyEntity> scoped = keys.stream()
                .filter(key -> channel == null || channel.equals(key.getGroupName()))
                .toList();
        return scoped.isEmpty() ? keys : scoped;
    }

    private List<ImpactRow> buildRows(UpstreamChangeEventEntity event, List<AccountApiKeyEntity> scoped,
                                      Map<Long, PoolAccountEntityView> poolByKey,
                                      Map<Long, List<Sub2GroupRatioReader.PlatformGroup>> groups) {
        if (scoped.isEmpty()) {
            return List.of();
        }
        BigDecimal before = decimal(event.getOldValue());
        BigDecimal after = decimal(event.getNewValue());
        if (after == null) {
            return List.of();
        }
        String channel = channelOf(event);
        boolean rising = before != null && after.compareTo(before) > 0;
        BigDecimal increase = percentIncrease(before, after);

        List<ImpactRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AccountApiKeyEntity key : scoped) {
            PoolAccountEntityView pool = poolByKey.get(key.getId());
            if (pool == null) {
                continue;
            }
            for (Sub2GroupRatioReader.PlatformGroup group : groups.getOrDefault(pool.externalAccountId(), List.of())) {
                if (!seen.add(pool.externalAccountId() + "|" + group.id())) {
                    continue;
                }
                boolean affected = rising && group.rateMultiplier() != null
                        && after.compareTo(group.rateMultiplier()) > 0;
                rows.add(new ImpactRow(
                        pool.name(),
                        pool.externalAccountId(),
                        group.name(),
                        group.rateMultiplier(),
                        channel,
                        before,
                        after,
                        increase,
                        affected,
                        levelOf(rising, affected, increase)));
            }
        }
        // 受影响的排前面：第一眼要看到的是「哪些分组已经倒挂」
        rows.sort(Comparator.comparing(ImpactRow::affected).reversed()
                .thenComparing(ImpactRow::groupRatio, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
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

    /** 本平台号池账号：keyId（account_api_keys.id）→ 账号。 */
    private Map<Long, PoolAccountEntityView> boundPoolAccounts() {
        Map<Long, PoolAccountEntityView> result = new HashMap<>();
        for (Integer platformId : poolAccountRepository.findDistinctPlatformIds()) {
            for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
                if (account.getBoundKeyId() == null || account.getExternalAccountId() == null) {
                    continue;
                }
                String name = account.getName() == null || account.getName().isBlank()
                        ? "号池账号 " + account.getExternalAccountId()
                        : account.getName();
                result.putIfAbsent(account.getBoundKeyId(),
                        new PoolAccountEntityView(name, account.getExternalAccountId()));
            }
        }
        return result;
    }

    /** 本平台号池账号的最小视图，避免把实体带出去。 */
    private record PoolAccountEntityView(String name, Long externalAccountId) {
    }
}