package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
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
 * 上游渠道倍率变化 → 我平台影响评估。
 *
 * <p>链路：上游渠道（upstream_groups）→ 该账号在该分组下的<strong>上游 Key</strong>
 * （account_api_keys）→ 绑定到的<strong>本平台号池账号</strong>
 * （pool_accounts.bound_key_id）→ 该账号所属<strong>分组</strong>
 * （Sub2API account_groups，多对多）→ 结算倍率。</p>
 *
 * <p><strong>只按分组倍率</strong>计算（业务上确认账号倍率不参与）。</p>
 *
 * <p><strong>是否受影响</strong>：上游渠道新倍率 <em>大于</em> 某个分组的倍率 ——
 * 也就是「按这个分组卖出去的价格，已经低于上游成本」。一个账号可能同时属于多个分组，
 * 所以结果是一行一个（账号 × 分组）。</p>
 *
 * <p>提醒级别：降价 → INFO；涨价但未超过任何分组 → WARNING；
 * 已倒挂 → WARNING（涨幅不足阈值）/ CRITICAL（涨幅达到阈值）。</p>
 */
@Service
public class RateChangeImpactService {

    /** 涨幅达到这个百分比时，受影响分组的提醒级别升到 CRITICAL。 */
    private static final BigDecimal CRITICAL_INCREASE_PERCENT = new BigDecimal("50");

    private final AccountApiKeyRepository apiKeyRepository;
    private final PoolAccountRepository poolAccountRepository;
    private final Sub2AccountGroupRateReader groupRateReader;
    private final UpstreamGroupRepository upstreamGroupRepository;

    public RateChangeImpactService(AccountApiKeyRepository apiKeyRepository,
                                   PoolAccountRepository poolAccountRepository,
                                   Sub2AccountGroupRateReader groupRateReader,
                                   UpstreamGroupRepository upstreamGroupRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.poolAccountRepository = poolAccountRepository;
        this.groupRateReader = groupRateReader;
        this.upstreamGroupRepository = upstreamGroupRepository;
    }

    /**
     * 一行 = 一个（本平台号池账号 × 所属分组）。
     *
     * @param accountName       本平台号池账号名
     * @param externalAccountId 本平台号池账号 ID（Sub2API account id）
     * @param groupName         所属分组名
     * @param groupRatio        分组倍率
     * @param upstreamChannel   发生变化的那个上游渠道名
     * @param oldRatio          上游原倍率
     * @param newRatio          上游新倍率
     * @param increasePercent   涨价幅度（%，涨为正值）
     * @param affected          上游新倍率是否已超过该分组的结算倍率
     * @param level             提醒级别：INFO / WARNING / CRITICAL
     */
    public record ImpactRow(String accountName, Long externalAccountId, String groupName, BigDecimal groupRatio,
                            String upstreamChannel,
                            BigDecimal oldRatio, BigDecimal newRatio, BigDecimal increasePercent,
                            boolean affected, String level) {
    }

    /** 这条变更的影响评估；不是渠道倍率事件时返回空列表。 */
    public List<ImpactRow> impactOf(UpstreamChangeEventEntity event) {
        if (event == null) {
            return List.of();
        }
        return impactsOf(List.of(event)).getOrDefault(event.getId(), List.of());
    }

    /**
     * 批量影响评估：分组倍率一次查完，避免变更列表（最多几百条）逐条打库。
     *
     * @return eventId → 影响行；只包含渠道倍率事件，其它事件不在返回结果里
     */
    public Map<Long, List<ImpactRow>> impactsOf(List<UpstreamChangeEventEntity> events) {
        if (events == null || events.isEmpty() || !groupRateReader.isAvailable()) {
            return Map.of();
        }
        List<UpstreamChangeEventEntity> rateEvents = events.stream()
                .filter(RateChangeImpactService::isRateChange)
                .toList();
        if (rateEvents.isEmpty()) {
            return Map.of();
        }

        Map<Long, PoolAccountView> poolByKey = boundPoolAccounts();
        Map<Integer, List<AccountApiKeyEntity>> keysByAccount = new HashMap<>();
        Map<Long, List<AccountApiKeyEntity>> scopedKeys = new HashMap<>();
        Set<Long> externalIds = new LinkedHashSet<>();
        for (UpstreamChangeEventEntity event : rateEvents) {
            List<AccountApiKeyEntity> keys = keysByAccount.computeIfAbsent(event.getAccountId(),
                    accountId -> apiKeyRepository.findByAccount(accountId, false));
            List<AccountApiKeyEntity> scoped = scopeKeys(keys, channelOf(event));
            scopedKeys.put(event.getId(), scoped);
            for (AccountApiKeyEntity key : scoped) {
                PoolAccountView pool = poolByKey.get(key.getId());
                if (pool != null) {
                    externalIds.add(pool.externalAccountId());
                }
            }
        }
        Map<Long, List<Sub2AccountGroupRateReader.AccountGroup>> groups =
                groupRateReader.groupsByAccount(externalIds);

        Map<Long, List<ImpactRow>> result = new LinkedHashMap<>();
        for (UpstreamChangeEventEntity event : rateEvents) {
            result.put(event.getId(), buildRows(event,
                    scopedKeys.getOrDefault(event.getId(), List.of()),
                    poolByKey,
                    groups,
                    channelPlatform(event)));
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

    /**
     * 上游渠道所属平台（openai / anthropic / grok …）。
     *
     * <p>用来把影响范围限定在<strong>同一平台</strong>的分组上：
     * 上游 codex 渠道涨价，不该落到我平台 kimi / grok 的分组上。</p>
     *
     * @return 取不到时返回 null（此时不按平台过滤，避免漏报）
     */
    private String channelPlatform(UpstreamChangeEventEntity event) {
        if (event.getAccountId() == null || event.getEntityKey() == null || event.getEntityKey().isBlank()) {
            return null;
        }
        return upstreamGroupRepository.findByAccountAndExternalId(event.getAccountId(), event.getPlatformType(), event.getEntityKey())
                .map(UpstreamGroupEntity::getPlatform)
                .filter(platform -> platform != null && !platform.isBlank())
                .orElse(null);
    }

    /** 分组平台与渠道平台不一致就跳过；任一为空时不过滤（避免漏报）。 */
    private static boolean matchesPlatform(String groupPlatform, String channelPlatform) {
        if (channelPlatform == null || groupPlatform == null || groupPlatform.isBlank()) {
            return true;
        }
        return groupPlatform.equalsIgnoreCase(channelPlatform);
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
                                      Map<Long, PoolAccountView> poolByKey,
                                      Map<Long, List<Sub2AccountGroupRateReader.AccountGroup>> groups,
                                      String channelPlatform) {
        if (scoped.isEmpty()) {
            return List.of();
        }
        BigDecimal before = decimal(event.getOldValue());
        BigDecimal after = decimal(event.getNewValue());
        if (after == null) {
            return List.of();
        }
        String upstreamChannel = channelOf(event);
        boolean rising = before != null && after.compareTo(before) > 0;
        BigDecimal increase = percentIncrease(before, after);

        List<ImpactRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AccountApiKeyEntity key : scoped) {
            PoolAccountView pool = poolByKey.get(key.getId());
            if (pool == null) {
                continue;
            }
            for (Sub2AccountGroupRateReader.AccountGroup group
                    : groups.getOrDefault(pool.externalAccountId(), List.of())) {
                if (!matchesPlatform(group.platform(), channelPlatform)) {
                    continue;
                }
                if (!seen.add(pool.externalAccountId() + "|" + group.groupId())) {
                    continue;
                }
                BigDecimal groupRatio = group.groupRatio();
                boolean affected = rising && groupRatio != null && after.compareTo(groupRatio) > 0;
                rows.add(new ImpactRow(
                        pool.name(),
                        pool.externalAccountId(),
                        group.groupName(),
                        groupRatio,
                        upstreamChannel,
                        before,
                        after,
                        increase,
                        affected,
                        levelOf(rising, affected, increase)));
            }
        }
        // 受影响的排前面、倍率高的排前面：第一眼要看到的是「哪些分组已经倒挂」
        rows.sort(Comparator.comparing(ImpactRow::affected).reversed()
                .thenComparing(ImpactRow::groupRatio, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /** 是否有分组「倒挂」（上游成本已高于分组倍率）。 */
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

    /**
     * 本平台号池账号：keyId（account_api_keys.id）→ 账号。
     *
     * <p>只收「允许调度」的账号：不可调度的号根本不接流量，
     * 上游渠道涨价轮不到它，算进来只会污染影响范围。</p>
     */
    private Map<Long, PoolAccountView> boundPoolAccounts() {
        Map<Long, PoolAccountView> result = new HashMap<>();
        for (Integer platformId : poolAccountRepository.findDistinctPlatformIds()) {
            for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
                if (account.getBoundKeyId() == null || account.getExternalAccountId() == null) {
                    continue;
                }
                if (!Boolean.TRUE.equals(account.getSchedulable())) {
                    continue;
                }
                String name = account.getName() == null || account.getName().isBlank()
                        ? "号池账号 " + account.getExternalAccountId()
                        : account.getName();
                result.putIfAbsent(account.getBoundKeyId(), new PoolAccountView(name, account.getExternalAccountId()));
            }
        }
        return result;
    }

    /** 本平台号池账号的最小视图，避免把实体带出去。 */
    private record PoolAccountView(String name, Long externalAccountId) {
    }
}