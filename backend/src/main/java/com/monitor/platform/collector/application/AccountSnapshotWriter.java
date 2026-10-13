package com.monitor.platform.collector.application;

import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiUser;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.common.util.Sha256Util;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号指标 / 用量看板快照写入。
 *
 * <p>把「上游响应 → 快照实体」的映射从采集编排里抽出来：采集负责取数与调度，
 * 这里只负责落库，改字段映射不必再动采集主流程。</p>
 */
@Component
public class AccountSnapshotWriter {

    private final AccountMetricSnapshotRepository metricSnapshotRepository;
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    private final CollectionJson json;

    public AccountSnapshotWriter(AccountMetricSnapshotRepository metricSnapshotRepository,
                                 AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                                 CollectionJson json) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.json = json;
    }

    /** New API 余额 / 额度快照。 */
    public void saveNewApiMetric(AccountEntity account, Long collectionRunId, NewApiSelfResponse response) {
        NewApiUser user = response.data();
        String rawData = json.toJson(response);

        AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(CollectionPlatforms.NEW_API);
        snapshot.setBalance(CollectionValues.newApiUsd(user.quota()));
        snapshot.setQuota(CollectionValues.decimal(user.quota()));
        snapshot.setUsedQuota(CollectionValues.decimal(user.usedQuota()));
        snapshot.setAffQuota(CollectionValues.decimal(user.affQuota()));
        snapshot.setAffHistoryQuota(CollectionValues.decimal(user.affHistoryQuota()));
        snapshot.setRequestCount(user.requestCount());
        snapshot.setQuotaUnit("USD");
        snapshot.setRawData(rawData);
        snapshot.setContentHash(Sha256Util.hex(rawData));
        metricSnapshotRepository.save(snapshot);
    }

    /** Sub2API 余额快照。 */
    public void saveSub2Metric(AccountEntity account, Long collectionRunId, Sub2ProfileResponse response) {
        Sub2ProfileResponse.UserProfile user = response.data();
        String rawData = json.toJson(response);

        AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(CollectionPlatforms.SUB2_API);
        snapshot.setBalance(CollectionValues.decimal(user.balance()));
        snapshot.setFrozenBalance(CollectionValues.decimal(user.frozenBalance()));
        snapshot.setQuotaUnit("USD");
        snapshot.setRawData(rawData);
        snapshot.setContentHash(Sha256Util.hex(rawData));
        metricSnapshotRepository.save(snapshot);
    }

    /**
     * 写入 New API 用量看板快照。
     *
     * <p>New API 只提供累计请求数与额度消耗，token 明细、今日统计、速率等字段留空，
     * 由前端按空值降级展示。</p>
     */
    public void saveNewApiUsageDashboard(AccountEntity account, Long collectionRunId, NewApiSelfResponse response) {
        NewApiUser user = response.data();
        BigDecimal usedCost = CollectionValues.newApiUsd(user.usedQuota());

        AccountUsageDashboardSnapshotEntity snapshot = new AccountUsageDashboardSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(CollectionPlatforms.NEW_API);
        snapshot.setBalance(CollectionValues.newApiUsd(user.quota()));
        snapshot.setTotalRequests(user.requestCount());
        snapshot.setTotalCost(usedCost);
        snapshot.setTotalActualCost(usedCost);
        snapshot.setMetrics(json.toJson(newApiUsageMetrics(user)));
        snapshot.setPlatformStats("[]");
        snapshot.setRawData(json.toJson(response));
        usageDashboardRepository.save(snapshot);
    }

    /**
     * 写入 Sub2API 用量看板快照。
     *
     * <p>看板响应中的 token 明细、今日统计、速率等平台特有指标写入 metrics，
     * 公共指标保持强类型列，便于跨平台统一查询。</p>
     */
    public void saveSub2UsageDashboard(AccountEntity account, Long collectionRunId,
                                       Sub2ProfileResponse profileResponse,
                                       Sub2UsageDashboardResponse response) {
        if (response.data() == null) {
            throw new IllegalStateException("Sub2API 用量看板响应缺少 data");
        }
        Sub2ProfileResponse.UserProfile profile = profileResponse.data();
        Sub2UsageDashboardResponse.DashboardStats stats = response.data();

        AccountUsageDashboardSnapshotEntity snapshot = new AccountUsageDashboardSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(CollectionPlatforms.SUB2_API);
        snapshot.setBalance(CollectionValues.decimal(profile.balance()));
        snapshot.setFrozenBalance(CollectionValues.decimal(profile.frozenBalance()));
        snapshot.setTotalRequests(stats.totalRequests());
        snapshot.setTotalTokens(stats.totalTokens());
        snapshot.setTotalCost(stats.totalCost());
        snapshot.setTotalActualCost(stats.totalActualCost());
        snapshot.setMetrics(json.toJson(sub2UsageMetrics(stats)));
        snapshot.setPlatformStats(json.toJson(stats.byPlatform() == null ? List.of() : stats.byPlatform()));
        snapshot.setRawData(json.toJson(response));
        usageDashboardRepository.save(snapshot);
    }

    /** New API 平台特有明细。额度保留上游原始 quota，quota_per_usd 供前端换算。 */
    private Map<String, Object> newApiUsageMetrics(NewApiUser user) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("quota", user.quota());
        metrics.put("used_quota", user.usedQuota());
        metrics.put("aff_quota", user.affQuota());
        metrics.put("aff_history_quota", user.affHistoryQuota());
        metrics.put("quota_unit", "USD");
        metrics.put("quota_per_usd", CollectionValues.newApiQuotaPerUsd());
        return metrics;
    }

    /** Sub2API 平台特有明细。 */
    private Map<String, Object> sub2UsageMetrics(Sub2UsageDashboardResponse.DashboardStats stats) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total_api_keys", stats.totalApiKeys());
        metrics.put("active_api_keys", stats.activeApiKeys());
        metrics.put("total_input_tokens", stats.totalInputTokens());
        metrics.put("total_output_tokens", stats.totalOutputTokens());
        metrics.put("total_cache_creation_tokens", stats.totalCacheCreationTokens());
        metrics.put("total_cache_read_tokens", stats.totalCacheReadTokens());
        metrics.put("today_requests", stats.todayRequests());
        metrics.put("today_input_tokens", stats.todayInputTokens());
        metrics.put("today_output_tokens", stats.todayOutputTokens());
        metrics.put("today_cache_creation_tokens", stats.todayCacheCreationTokens());
        metrics.put("today_cache_read_tokens", stats.todayCacheReadTokens());
        metrics.put("today_tokens", stats.todayTokens());
        metrics.put("today_cost", stats.todayCost());
        metrics.put("today_actual_cost", stats.todayActualCost());
        metrics.put("average_duration_ms", stats.averageDurationMs());
        metrics.put("rpm", stats.rpm());
        metrics.put("tpm", stats.tpm());
        return metrics;
    }
}
