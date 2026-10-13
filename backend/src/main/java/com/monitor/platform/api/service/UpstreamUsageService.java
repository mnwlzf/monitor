package com.monitor.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.platform.api.dto.AccountMetricPointResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.time.BusinessTime;
import com.monitor.platform.pool.RateChangeImpactService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游用量与变更查询：用量看板、余额指标时序、渠道变更事件。
 *
 * <p>从 {@link UpstreamAdminService} 抽出：管理与写入留在管理服务，
 * 这里只放「读多写零」的统计查询，时区、New API 今日消耗推导、图表抽稀等口径集中在一处。</p>
 */
@Service
public class UpstreamUsageService {

    /** 指标时序单次最多查询的原始点数。 */
    private static final int METRIC_MAX_QUERY_POINTS = 10000;

    /** 折线图最多返回的点数，超出时按步长抽稀，避免前端渲染过慢。 */
    private static final int METRIC_MAX_CHART_POINTS = 400;

    /** New API 平台标识，其「今日消耗」由累计消耗差值推算。 */
    private static final String PLATFORM_NEW_API = "newapi";

    /** 日统计所属时区，与上游采集口径保持一致。 */
    private static final ZoneId REPORT_ZONE = BusinessTime.ZONE;

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountMetricSnapshotRepository metricSnapshotRepository;
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    private final UpstreamChangeEventRepository changeEventRepository;
    private final JsonPayloads json;
    private final UpstreamAccountAccess accounts;
    private final RateChangeImpactService rateChangeImpactService;

    public UpstreamUsageService(PlatformRepository platformRepository,
                                AccountRepository accountRepository,
                                AccountMetricSnapshotRepository metricSnapshotRepository,
                                AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                                UpstreamChangeEventRepository changeEventRepository,
                                JsonPayloads json,
                                UpstreamAccountAccess accounts,
                                RateChangeImpactService rateChangeImpactService) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.changeEventRepository = changeEventRepository;
        this.json = json;
        this.accounts = accounts;
        this.rateChangeImpactService = rateChangeImpactService;
    }

    /**
     * 查询平台下最近的渠道变更事件，并按发现时间倒序截断。
     *
     * @param platformId 平台 ID
     * @param limit      最大返回条数
     * @return 变更事件列表
     */
    public List<UpstreamChangeEventResponse> listChanges(Integer platformId, int limit) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<UpstreamChangeEventEntity> events = new ArrayList<>();
        for (AccountEntity account : accountRepository.findByPlatformId(platformId)) {
            events.addAll(changeEventRepository.findRecent(account.getId(), safeLimit));
        }
        events.sort(Comparator.comparing(UpstreamChangeEventEntity::getDetectedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<UpstreamChangeEventEntity> shown = events.stream().limit(safeLimit).toList();
        // 渠道倍率事件顺带评估「我平台哪些分组倒挂」；批量做，避免逐条打库
        Map<Long, List<RateChangeImpactService.ImpactRow>> impacts =
                rateChangeImpactService.impactsOf(shown);
        return shown.stream()
                .map(event -> toChangeEventResponse(event, impacts.getOrDefault(event.getId(), List.of())))
                .toList();
    }

    /**
     * 查询平台下各账号的最新用量看板快照。
     *
     * <p>newapi 与 sub2api 使用同一张快照表，接口返回统一结构；
     * 平台未提供的指标为 null，由前端降级展示。</p>
     */
    public List<AccountUsageDashboardResponse> listUsageDashboard(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        List<AccountEntity> accounts = accountRepository.findByPlatformId(platformId);
        Map<Integer, AccountEntity> accountMap = new HashMap<>();
        for (AccountEntity account : accounts) {
            accountMap.put(account.getId(), account);
        }
        if (accountMap.isEmpty()) {
            return List.of();
        }

        List<Integer> accountIds = new ArrayList<>(accountMap.keySet());
        Map<Integer, BigDecimal> newApiTodayCost = calculateNewApiTodayCost(accountIds);

        List<AccountUsageDashboardResponse> result = new ArrayList<>();
        for (AccountUsageDashboardSnapshotEntity snapshot
                : usageDashboardRepository.findLatestByAccounts(accountIds)) {
            AccountEntity account = accountMap.get(snapshot.getAccountId());
            if (account == null) {
                continue;
            }
            // New API 上游没有当日字段，用当天累计消耗差值补出 metrics.today_actual_cost；
            // totalActualCost 仍保持「累计实际成本」语义，不被覆盖。
            JsonNode metrics = json.read(snapshot.getMetrics());
            if (PLATFORM_NEW_API.equalsIgnoreCase(snapshot.getPlatformType())) {
                metrics = withTodayActualCost(metrics, newApiTodayCost.get(snapshot.getAccountId()));
            }
            result.add(new AccountUsageDashboardResponse(
                    snapshot.getId(),
                    snapshot.getAccountId(),
                    account.getPlatformId(),
                    account.getDisplayName(),
                    snapshot.getPlatformType(),
                    snapshot.getBalance(),
                    snapshot.getFrozenBalance(),
                    snapshot.getTotalRequests(),
                    snapshot.getTotalTokens(),
                    snapshot.getTotalCost(),
                    snapshot.getTotalActualCost(),
                    metrics,
                    json.read(snapshot.getPlatformStats()),
                    snapshot.getCollectedAt()
            ));
        }
        return result;
    }

    /**
     * 计算 New API 账号的「今日消耗」。
     *
     * <p>New API 只提供累计已用额度，没有当日统计，因此用当天首次采集与最新一次采集的
     * 累计消耗（已换算 USD 的 total_cost）差值作为今日消耗；额度被重置或换套餐导致
     * 差值为负时跳过，避免展示误导数据。</p>
     *
     * @return accountId -> 今日消耗（USD），无法计算时不包含该账号
     */
    private Map<Integer, BigDecimal> calculateNewApiTodayCost(List<Integer> accountIds) {
        OffsetDateTime now = OffsetDateTime.now(REPORT_ZONE);
        OffsetDateTime dayStart = LocalDate.now(REPORT_ZONE).atStartOfDay(REPORT_ZONE).toOffsetDateTime();
        List<AccountUsageDashboardSnapshotEntity> snapshots =
                usageDashboardRepository.findByAccounts(accountIds, dayStart, now);
        if (snapshots.isEmpty()) {
            return Map.of();
        }

        Map<Integer, BigDecimal> first = new HashMap<>();
        Map<Integer, BigDecimal> last = new HashMap<>();
        for (AccountUsageDashboardSnapshotEntity snapshot : snapshots) {
            if (!PLATFORM_NEW_API.equalsIgnoreCase(snapshot.getPlatformType())
                    || snapshot.getTotalCost() == null) {
                continue;
            }
            first.putIfAbsent(snapshot.getAccountId(), snapshot.getTotalCost());
            last.put(snapshot.getAccountId(), snapshot.getTotalCost());
        }

        Map<Integer, BigDecimal> result = new HashMap<>();
        for (Map.Entry<Integer, BigDecimal> entry : last.entrySet()) {
            BigDecimal firstCost = first.get(entry.getKey());
            if (firstCost == null) {
                continue;
            }
            BigDecimal delta = entry.getValue().subtract(firstCost);
            if (delta.signum() < 0) {
                continue;
            }
            result.put(entry.getKey(), delta);
        }
        return result;
    }

    /**
     * 把推导出的当日消耗写回 metrics，前端统一读取 today_actual_cost。
     */
    private JsonNode withTodayActualCost(JsonNode metrics, BigDecimal todayActualCost) {
        if (todayActualCost == null) {
            return metrics;
        }
        ObjectNode node = metrics != null && metrics.isObject()
                ? ((ObjectNode) metrics).deepCopy()
                : json.newObject();
        node.put("today_actual_cost", todayActualCost);
        return node;
    }

    /**
     * 查询账号余额/额度指标时序，供余额详情页折线图使用。
     *
     * <p>时间维度支持 {@code 1d}、{@code 7d}、{@code 30d}、{@code 90d}，默认 {@code 7d}。
     * 点数过多时按步长抽稀到 {@value #METRIC_MAX_CHART_POINTS} 以内，保留首尾点。</p>
     *
     * @param platformId 平台 ID
     * @param accountId  账号 ID
     * @param range      时间维度
     * @return 指标时序点（按时间升序）
     */
    public List<AccountMetricPointResponse> listAccountMetrics(Integer platformId, Integer accountId, String range) {
        accounts.require(platformId, accountId);

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(resolveMetricRange(range));
        List<AccountMetricSnapshotEntity> snapshots = metricSnapshotRepository
                .findByAccount(accountId, from, to, METRIC_MAX_QUERY_POINTS);

        return downsampleMetrics(snapshots, METRIC_MAX_CHART_POINTS).stream()
                .map(this::toMetricPointResponse)
                .toList();
    }

    /** 解析时间维度参数。 */
    private Duration resolveMetricRange(String range) {
        if (range == null) {
            return Duration.ofDays(7);
        }
        return switch (range.trim().toLowerCase()) {
            case "1d", "24h" -> Duration.ofDays(1);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            default -> Duration.ofDays(7);
        };
    }

    /**
     * 等步长抽稀指标点：超过上限时按比例采样，并始终保留最后一个点。
     */
    private List<AccountMetricSnapshotEntity> downsampleMetrics(List<AccountMetricSnapshotEntity> points, int maxPoints) {
        if (points.size() <= maxPoints || maxPoints <= 1) {
            return points;
        }
        List<AccountMetricSnapshotEntity> result = new ArrayList<>(maxPoints + 1);
        int step = (int) Math.ceil((double) points.size() / maxPoints);
        for (int i = 0; i < points.size(); i += step) {
            result.add(points.get(i));
        }
        AccountMetricSnapshotEntity last = points.get(points.size() - 1);
        if (!result.get(result.size() - 1).equals(last)) {
            result.add(last);
        }
        return result;
    }

    private AccountMetricPointResponse toMetricPointResponse(AccountMetricSnapshotEntity snapshot) {
        return new AccountMetricPointResponse(
                snapshot.getCollectedAt(),
                snapshot.getBalance(),
                snapshot.getFrozenBalance(),
                snapshot.getQuota(),
                snapshot.getUsedQuota(),
                snapshot.getRequestCount(),
                snapshot.getQuotaUnit());
    }

    /**
     * 将变更事件实体转换为接口响应。
     */
    private UpstreamChangeEventResponse toChangeEventResponse(UpstreamChangeEventEntity entity,
                                                              List<RateChangeImpactService.ImpactRow> impacts) {
        return new UpstreamChangeEventResponse(
                entity.getId(), entity.getAccountId(), entity.getPlatformType(),
                entity.getEntityType(), entity.getEntityKey(), entity.getGroupName(), entity.getAccountName(),
                entity.getKeyName(), entity.getUpstreamKeyNames(), entity.getLocalKeyNames(),
                entity.getGroupRatio(), entity.getChangeType(),
                entity.getFieldName(), entity.getOldValue(), entity.getNewValue(),
                entity.getSeverity(), entity.getMessage(), entity.getDetectedAt(),
                Boolean.TRUE.equals(entity.getInUse()),
                impacts.stream().map(UpstreamUsageService::toChangeImpact).toList()
        );
    }

    private static UpstreamChangeEventResponse.ChangeImpact toChangeImpact(RateChangeImpactService.ImpactRow row) {
        return new UpstreamChangeEventResponse.ChangeImpact(
                row.accountName(),
                row.externalAccountId(),
                row.upstreamKeyName(),
                row.groupName(),
                row.groupRatio(),
                row.oldRatio(),
                row.newRatio(),
                row.increasePercent(),
                row.affected(),
                row.level());
    }
}