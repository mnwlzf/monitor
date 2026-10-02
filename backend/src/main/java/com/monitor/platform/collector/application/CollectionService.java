package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.adapter.newapi.NewApiAdapter;
import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiUser;
import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.UpstreamGroupSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupSnapshotEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 上游采集业务服务。
 *
 * <p>负责账号调度、凭证解析、适配器调用、指标快照、渠道状态同步和变更事件记录。
 * 网络调用不包在长事务中，持久化由各 Repository 独立完成。</p>
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private static final String PLATFORM_NEW_API = "newapi";
    private static final String PLATFORM_SUB2_API = "sub2api";
    private static final String COLLECT_STATUS_RUNNING = "RUNNING";
    private static final String COLLECT_STATUS_SUCCESS = "SUCCESS";
    private static final String COLLECT_STATUS_FAILED = "FAILED";
    private static final String CREDENTIAL_STATUS_VALID = "VALID";
    private static final String ENTITY_TYPE_GROUP = "GROUP";
    private static final String CHANGE_GROUP_ADDED = "GROUP_ADDED";
    private static final String CHANGE_GROUP_REMOVED = "GROUP_REMOVED";
    private static final String CHANGE_GROUP_UPDATED = "GROUP_UPDATED";
    private static final String CHANGE_RATE_CHANGED = "RATE_CHANGED";
    private static final String CHANGE_BASE_RATE_CHANGED = "BASE_RATE_CHANGED";
    private static final String CHANGE_STATUS_CHANGED = "STATUS_CHANGED";
    private static final Duration DEFAULT_COLLECT_INTERVAL = Duration.ofMinutes(10);
    private static final BigDecimal NEW_API_QUOTA_PER_USD = BigDecimal.valueOf(500000L);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final AccountRepository accountRepository;
    private final PlatformRepository platformRepository;
    private final AccountCredentialService credentialService;
    private final CollectionRunRepository collectionRunRepository;
    private final AccountMetricSnapshotRepository metricSnapshotRepository;
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    private final UpstreamGroupRepository groupRepository;
    private final UpstreamGroupSnapshotRepository groupSnapshotRepository;
    private final UpstreamChangeEventRepository changeEventRepository;
    private final NewApiAdapter newApiAdapter;
    private final Sub2ApiAdapter sub2ApiAdapter;
    private final ObjectMapper objectMapper;

    public CollectionService(AccountRepository accountRepository,
                             PlatformRepository platformRepository,
                             AccountCredentialService credentialService,
                             CollectionRunRepository collectionRunRepository,
                             AccountMetricSnapshotRepository metricSnapshotRepository,
                             AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                             UpstreamGroupRepository groupRepository,
                             UpstreamGroupSnapshotRepository groupSnapshotRepository,
                             UpstreamChangeEventRepository changeEventRepository,
                             NewApiAdapter newApiAdapter,
                             Sub2ApiAdapter sub2ApiAdapter,
                             ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
        this.credentialService = credentialService;
        this.collectionRunRepository = collectionRunRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.groupRepository = groupRepository;
        this.groupSnapshotRepository = groupSnapshotRepository;
        this.changeEventRepository = changeEventRepository;
        this.newApiAdapter = newApiAdapter;
        this.sub2ApiAdapter = sub2ApiAdapter;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集一批到期账号。单个账号失败不会阻断其他账号。
     */
    public void collectDueAccounts(int limit) {
        List<PlatformEntity> platforms = platformRepository.findEnabled();
        log.info("开始执行账号采集任务: enabledPlatforms={}, accountLimitPerPlatform={}",
                platforms.size(), limit);

        for (PlatformEntity platform : platforms) {
            log.info("平台采集开始: platformId={}, platformName={}, platformType={}, baseUrl={}",
                    platform.getId(), platform.getPlatformName(), platform.getPlatformType(), platform.getUrl());

            List<AccountEntity> accounts = accountRepository.findDueForCollection(
                    platform.getId(), OffsetDateTime.now(), limit);
            log.info("平台待采集账号: platformId={}, count={}", platform.getId(), accounts.size());

            for (AccountEntity account : accounts) {
                try {
                    collectAccount(account.getId());
                } catch (Exception ex) {
                    log.error("账号采集失败，继续处理下一个: platformId={}, accountId={}",
                            platform.getId(), account.getId(), ex);
                }
            }

            log.info("平台采集结束: platformId={}, platformName={}, processedAccounts={}",
                    platform.getId(), platform.getPlatformName(), accounts.size());
        }

        log.info("账号采集任务结束: enabledPlatforms={}", platforms.size());
    }

    /**
     * 采集单个账号，并写入指标、渠道、快照和变更事件。
     */
    public void collectAccount(Integer accountId) {
        collectAccount(null, accountId);
    }

    /**
     * 采集指定平台下的单个账号。
     */
    public void collectAccount(Integer platformId, Integer accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: " + accountId));
        if (platformId != null && !platformId.equals(account.getPlatformId())) {
            throw new IllegalArgumentException("账号不属于指定平台: " + accountId);
        }
        PlatformEntity platform = platformRepository.findById(account.getPlatformId())
                .orElseThrow(() -> new IllegalArgumentException("账号未关联平台: " + accountId));

        String platformType = platform.getPlatformType();
        log.info("账号采集开始: accountId={}, platformId={}, platformType={}, account={}",
                accountId, platform.getId(), platformType,
                StrUtil.blankToDefault(account.getUsername(), account.getEmail()));

        CollectionRunEntity run = collectionRunRepository.start(accountId, platformType, "{}");
        log.info("采集批次已创建: accountId={}, runId={}", accountId, run.getId());
        long startedMillis = System.currentTimeMillis();

        try {
            if (PLATFORM_NEW_API.equalsIgnoreCase(platformType)) {
                collectNewApi(account, platform, run.getId());
            } else if (PLATFORM_SUB2_API.equalsIgnoreCase(platformType)) {
                collectSub2Api(account, platform, run.getId());
            } else {
                throw new IllegalArgumentException("不支持的平台类型: " + platformType);
            }

            markAccountSuccess(account);
            long durationMs = System.currentTimeMillis() - startedMillis;
            collectionRunRepository.finish(
                    run.getId(),
                    COLLECT_STATUS_SUCCESS,
                    OffsetDateTime.now(),
                    durationMs,
                    null,
                    null
            );
            log.info("账号采集成功: accountId={}, runId={}, durationMs={}", accountId, run.getId(), durationMs);
        } catch (Exception ex) {
            markAccountFailure(account, ex);
            long durationMs = System.currentTimeMillis() - startedMillis;
            collectionRunRepository.finish(
                    run.getId(),
                    COLLECT_STATUS_FAILED,
                    OffsetDateTime.now(),
                    durationMs,
                    "COLLECTION_FAILED",
                    truncate(ex.getMessage(), MAX_ERROR_LENGTH)
            );
            log.error("账号采集失败: accountId={}, runId={}, durationMs={}",
                    accountId, run.getId(), durationMs, ex);
            throw new IllegalStateException("账号采集失败: " + accountId, ex);
        }
    }

    private void collectNewApi(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API 账号信息获取开始: accountId={}, baseUrl={}, username={}",
                account.getId(), baseUrl, username);

        NewApiSelfResponse selfResponse = newApiAdapter.fetchSelf(baseUrl, username, password);
        saveNewApiMetric(account, collectionRunId, selfResponse);
        saveNewApiUsageDashboard(account, collectionRunId, selfResponse);
        log.info("New API 账号信息获取成功: accountId={}, userId={}, quota={}, usedQuota={}",
                account.getId(), selfResponse.data().id(), selfResponse.data().quota(), selfResponse.data().usedQuota());
        if (selfResponse.data().id() != null) {
            account.setExternalUserId(String.valueOf(selfResponse.data().id()));
        }

        NewApiGroupsResponse groupsResponse = newApiAdapter.fetchGroups(baseUrl, username, password);
        syncNewApiGroups(account, platform.getPlatformType(), collectionRunId, groupsResponse);
    }

    private void collectSub2Api(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API 账号信息获取开始: accountId={}, baseUrl={}, email={}",
                account.getId(), baseUrl, email);

        Sub2ProfileResponse profileResponse = sub2ApiAdapter.fetchProfile(baseUrl, email, password);
        if (profileResponse.data() == null) {
            throw new IllegalStateException("Sub2API 个人信息响应缺少 data");
        }
        saveSub2Metric(account, collectionRunId, profileResponse);
        log.info("Sub2API 账号信息获取成功: accountId={}, userId={}, balance={}, frozenBalance={}",
                account.getId(), profileResponse.data().id(),
                profileResponse.data().balance(), profileResponse.data().frozenBalance());
        if (profileResponse.data().id() != null) {
            account.setExternalUserId(String.valueOf(profileResponse.data().id()));
        }

        Sub2GroupsResponse groupsResponse = sub2ApiAdapter.fetchAvailableGroups(baseUrl, email, password);
        syncSub2Groups(account, platform.getPlatformType(), collectionRunId, groupsResponse);

        Sub2UsageDashboardResponse usageResponse =
                sub2ApiAdapter.fetchUsageDashboardStats(baseUrl, email, password);
        saveSub2UsageDashboard(account, collectionRunId, profileResponse, usageResponse);
    }

    private void saveNewApiMetric(AccountEntity account, Long collectionRunId,
                                  NewApiSelfResponse response) {
        NewApiUser user = response.data();
        String rawData = toJson(response);
        BigDecimal quota = decimal(user.quota());
        BigDecimal balance = newApiUsd(user.quota());

        AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(PLATFORM_NEW_API);
        snapshot.setBalance(balance);
        snapshot.setQuota(quota);
        snapshot.setUsedQuota(decimal(user.usedQuota()));
        snapshot.setAffQuota(decimal(user.affQuota()));
        snapshot.setAffHistoryQuota(decimal(user.affHistoryQuota()));
        snapshot.setRequestCount(user.requestCount());
        snapshot.setQuotaUnit("USD");
        snapshot.setRawData(rawData);
        snapshot.setContentHash(sha256(rawData));
        metricSnapshotRepository.save(snapshot);
    }

    private void saveSub2Metric(AccountEntity account, Long collectionRunId,
                                Sub2ProfileResponse response) {
        Sub2ProfileResponse.UserProfile user = response.data();
        String rawData = toJson(response);

        AccountMetricSnapshotEntity snapshot = new AccountMetricSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(PLATFORM_SUB2_API);
        snapshot.setBalance(decimal(user.balance()));
        snapshot.setFrozenBalance(decimal(user.frozenBalance()));
        snapshot.setQuotaUnit("USD");
        snapshot.setRawData(rawData);
        snapshot.setContentHash(sha256(rawData));
        metricSnapshotRepository.save(snapshot);
    }

    /**
     * 写入 New API 用量看板快照。
     *
     * <p>New API 只提供累计请求数与额度消耗，token 明细、今日统计、速率等字段留空，
     * 由前端按空值降级展示。</p>
     */
    private void saveNewApiUsageDashboard(AccountEntity account, Long collectionRunId,
                                          NewApiSelfResponse response) {
        NewApiUser user = response.data();
        BigDecimal usedCost = newApiUsd(user.usedQuota());

        AccountUsageDashboardSnapshotEntity snapshot = new AccountUsageDashboardSnapshotEntity();
        snapshot.setAccountId(account.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(PLATFORM_NEW_API);
        snapshot.setBalance(newApiUsd(user.quota()));
        snapshot.setTotalRequests(user.requestCount());
        snapshot.setTotalCost(usedCost);
        snapshot.setTotalActualCost(usedCost);
        snapshot.setMetrics(toJson(newApiUsageMetrics(user)));
        snapshot.setPlatformStats("[]");
        snapshot.setRawData(toJson(response));
        usageDashboardRepository.save(snapshot);
    }

    /**
     * 写入 Sub2API 用量看板快照。
     *
     * <p>看板响应中的 token 明细、今日统计、速率等平台特有指标写入 metrics，
     * 公共指标保持强类型列，便于跨平台统一查询。</p>
     */
    private void saveSub2UsageDashboard(AccountEntity account, Long collectionRunId,
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
        snapshot.setPlatformType(PLATFORM_SUB2_API);
        snapshot.setBalance(decimal(profile.balance()));
        snapshot.setFrozenBalance(decimal(profile.frozenBalance()));
        snapshot.setTotalRequests(stats.totalRequests());
        snapshot.setTotalTokens(stats.totalTokens());
        snapshot.setTotalCost(stats.totalCost());
        snapshot.setTotalActualCost(stats.totalActualCost());
        snapshot.setMetrics(toJson(sub2UsageMetrics(stats)));
        snapshot.setPlatformStats(toJson(stats.byPlatform() == null ? List.of() : stats.byPlatform()));
        snapshot.setRawData(toJson(response));
        usageDashboardRepository.save(snapshot);
    }

    /**
     * New API 平台特有明细。额度保留上游原始 quota，quota_per_usd 供前端换算。
     */
    private Map<String, Object> newApiUsageMetrics(NewApiUser user) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("quota", user.quota());
        metrics.put("used_quota", user.usedQuota());
        metrics.put("aff_quota", user.affQuota());
        metrics.put("aff_history_quota", user.affHistoryQuota());
        metrics.put("quota_unit", "USD");
        metrics.put("quota_per_usd", NEW_API_QUOTA_PER_USD);
        return metrics;
    }

    /**
     * Sub2API 平台特有明细。
     */
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

    /**
     * 将 New API 原始 quota 换算为 USD，为空时返回 null。
     */
    private BigDecimal newApiUsd(Long quota) {
        BigDecimal value = decimal(quota);
        return value == null ? null : value.divide(NEW_API_QUOTA_PER_USD, 8, RoundingMode.HALF_UP);
    }

    private void syncNewApiGroups(AccountEntity account, String platformType,
                                  Long collectionRunId, NewApiGroupsResponse response) {
        if (response.data() == null) {
            return;
        }

        Map<String, UpstreamGroupEntity> existing = activeGroupMap(account.getId());
        Set<String> activeExternalIds = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        int added = 0;
        int updated = 0;

        for (Map.Entry<String, NewApiGroupsResponse.Group> entry : response.data().entrySet()) {
            String externalGroupId = entry.getKey();
            NewApiGroupsResponse.Group group = entry.getValue();
            activeExternalIds.add(externalGroupId);

            UpstreamGroupEntity entity = existing.get(externalGroupId);
            boolean isNew = entity == null;
            if (isNew) {
                entity = newGroup(account, platformType, externalGroupId, now);
            }

            BigDecimal ratio = decimal(group.ratio());
            BigDecimal baseRatio = decimal(group.baseRatio());
            String status = Boolean.TRUE.equals(group.scheduleActive()) ? "SCHEDULE_ACTIVE" : "ACTIVE";
            String rawData = toJson(group);
            boolean changed = isNew;

            if (!isNew) {
                changed = recordGroupChanges(account, platformType, collectionRunId, entity,
                        externalGroupId, ratio, baseRatio, status,
                        externalGroupId, null, group.desc());
            }

            entity.setGroupName(externalGroupId);
            entity.setDescription(group.desc());
            entity.setCurrentRatio(ratio);
            entity.setCurrentBaseRatio(baseRatio);
            entity.setStatus(status);
            entity.setIsActive(true);
            entity.setLastSeenAt(now);
            entity.setMetadata(rawData);
            if (changed) {
                entity.setLastChangedAt(now);
            }
            groupRepository.save(entity);

            if (isNew) {
                added++;
                recordChange(account, platformType, collectionRunId, entity, CHANGE_GROUP_ADDED,
                        null, null, rawData, "渠道新增: " + externalGroupId);
            } else if (changed) {
                updated++;
            }

            saveGroupSnapshot(entity, collectionRunId, ratio, baseRatio, status, true, rawData);
        }

        int removed = deactivateMissingGroups(account, platformType, collectionRunId, existing, activeExternalIds, now);
        log.info("New API 分组同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), response.data().size(), added, updated, removed);
    }

    private void syncSub2Groups(AccountEntity account, String platformType,
                                Long collectionRunId, Sub2GroupsResponse response) {
        if (response.data() == null) {
            return;
        }

        Map<String, UpstreamGroupEntity> existing = activeGroupMap(account.getId());
        Set<String> activeExternalIds = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        int added = 0;
        int updated = 0;

        for (Sub2GroupsResponse.Group group : response.data()) {
            String externalGroupId = String.valueOf(group.id());
            activeExternalIds.add(externalGroupId);

            UpstreamGroupEntity entity = existing.get(externalGroupId);
            boolean isNew = entity == null;
            if (isNew) {
                entity = newGroup(account, platformType, externalGroupId, now);
            }

            BigDecimal ratio = decimal(group.rateMultiplier());
            String rawData = toJson(group);
            boolean changed = isNew;

            if (!isNew) {
                changed = recordGroupChanges(account, platformType, collectionRunId, entity,
                        externalGroupId, ratio, null, group.status(),
                        group.name(), group.platform(), group.description());
            }

            entity.setGroupName(group.name());
            entity.setDescription(group.description());
            entity.setPlatform(group.platform());
            entity.setCurrentRatio(ratio);
            entity.setCurrentBaseRatio(null);
            entity.setStatus(group.status());
            entity.setIsActive(true);
            entity.setLastSeenAt(now);
            entity.setMetadata(rawData);
            if (changed) {
                entity.setLastChangedAt(now);
            }
            groupRepository.save(entity);

            if (isNew) {
                added++;
                recordChange(account, platformType, collectionRunId, entity, CHANGE_GROUP_ADDED,
                        null, null, rawData, "渠道新增: " + group.name());
            } else if (changed) {
                updated++;
            }

            saveGroupSnapshot(entity, collectionRunId, ratio, null, group.status(), true, rawData);
        }

        int removed = deactivateMissingGroups(account, platformType, collectionRunId, existing, activeExternalIds, now);
        log.info("Sub2API 分组同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), response.data().size(), added, updated, removed);
    }

    private Map<String, UpstreamGroupEntity> activeGroupMap(Integer accountId) {
        Map<String, UpstreamGroupEntity> result = new HashMap<>();
        for (UpstreamGroupEntity group : groupRepository.findByAccount(accountId, true)) {
            result.put(group.getExternalGroupId(), group);
        }
        return result;
    }

    private UpstreamGroupEntity newGroup(AccountEntity account, String platformType,
                                         String externalGroupId, OffsetDateTime now) {
        UpstreamGroupEntity entity = new UpstreamGroupEntity();
        entity.setAccountId(account.getId());
        entity.setPlatformType(platformType);
        entity.setExternalGroupId(externalGroupId);
        entity.setIsActive(true);
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setLastChangedAt(now);
        return entity;
    }

    private int deactivateMissingGroups(AccountEntity account, String platformType,
                                         Long collectionRunId,
                                         Map<String, UpstreamGroupEntity> existing,
                                         Set<String> activeExternalIds,
                                         OffsetDateTime now) {
        int removed = 0;
        for (UpstreamGroupEntity group : existing.values()) {
            if (activeExternalIds.contains(group.getExternalGroupId())) {
                continue;
            }
            group.setIsActive(false);
            group.setLastChangedAt(now);
            groupRepository.save(group);
            recordChange(account, platformType, collectionRunId, group, CHANGE_GROUP_REMOVED,
                    "is_active", true, false, "渠道已下线: " + group.getGroupName());
            saveGroupSnapshot(group, collectionRunId, group.getCurrentRatio(),
                    group.getCurrentBaseRatio(), group.getStatus(), false, "{}");
            removed++;
        }
        return removed;
    }

    private boolean recordGroupChanges(AccountEntity account, String platformType,
                                       Long collectionRunId, UpstreamGroupEntity entity,
                                       String externalGroupId, BigDecimal ratio,
                                       BigDecimal baseRatio, String status,
                                       String groupName, String platform, String description) {
        boolean changed = false;
        if (different(entity.getCurrentRatio(), ratio)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_RATE_CHANGED,
                    "ratio", entity.getCurrentRatio(), ratio, "倍率变化: " + externalGroupId);
            changed = true;
        }
        if (different(entity.getCurrentBaseRatio(), baseRatio)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_BASE_RATE_CHANGED,
                    "base_ratio", entity.getCurrentBaseRatio(), baseRatio,
                    "基础倍率变化: " + externalGroupId);
            changed = true;
        }
        if (!Objects.equals(entity.getStatus(), status)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_STATUS_CHANGED,
                    "status", entity.getStatus(), status, "状态变化: " + externalGroupId);
            changed = true;
        }
        if (!Objects.equals(entity.getGroupName(), groupName)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_GROUP_UPDATED,
                    "name", entity.getGroupName(), groupName, "渠道名称变化: " + externalGroupId);
            changed = true;
        }
        if (!Objects.equals(entity.getPlatform(), platform)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_GROUP_UPDATED,
                    "platform", entity.getPlatform(), platform, "渠道平台变化: " + externalGroupId);
            changed = true;
        }
        if (!Objects.equals(entity.getDescription(), description)) {
            recordChange(account, platformType, collectionRunId, entity, CHANGE_GROUP_UPDATED,
                    "description", entity.getDescription(), description, "渠道描述变化: " + externalGroupId);
            changed = true;
        }
        return changed;
    }
    private void saveGroupSnapshot(UpstreamGroupEntity group, Long collectionRunId,
                                   BigDecimal ratio, BigDecimal baseRatio, String status,
                                   boolean active, String rawData) {
        UpstreamGroupSnapshotEntity snapshot = new UpstreamGroupSnapshotEntity();
        snapshot.setGroupId(group.getId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setRatio(ratio);
        snapshot.setBaseRatio(baseRatio);
        snapshot.setStatus(status);
        snapshot.setIsActive(active);
        snapshot.setRawData(rawData);
        snapshot.setContentHash(sha256(rawData));
        groupSnapshotRepository.save(snapshot);
    }

    private void recordChange(AccountEntity account, String platformType, Long collectionRunId,
                              UpstreamGroupEntity entity, String changeType,
                              String fieldName, Object oldValue, Object newValue,
                              String message) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setAccountId(account.getId());
        event.setPlatformType(platformType);
        event.setCollectionRunId(collectionRunId);
        event.setEntityType(ENTITY_TYPE_GROUP);
        event.setEntityId(entity.getId());
        event.setEntityKey(entity.getExternalGroupId());
        event.setChangeType(changeType);
        event.setFieldName(fieldName);
        event.setOldValue(toJson(oldValue));
        event.setNewValue(toJson(newValue));
        event.setSeverity("INFO");
        event.setMessage(message);
        event.setDetectedAt(OffsetDateTime.now());
        event.setMetadata("{}");
        log.info("记录渠道变更事件: accountId={}, runId={}, platformType={}, changeType={}, field={}, key={}, oldValue={}, newValue={}",
                account.getId(), collectionRunId, platformType, changeType, fieldName,
                entity.getExternalGroupId(), event.getOldValue(), event.getNewValue());
        changeEventRepository.save(event);
    }

    private void markAccountSuccess(AccountEntity account) {
        OffsetDateTime now = OffsetDateTime.now();
        account.setCredentialStatus(CREDENTIAL_STATUS_VALID);
        account.setLastCollectedAt(now);
        account.setLastCollectStatus(COLLECT_STATUS_SUCCESS);
        account.setLastCollectError(null);
        account.setConsecutiveFailures(0);
        account.setNextCollectAt(now.plus(DEFAULT_COLLECT_INTERVAL));
        accountRepository.save(account);
    }

    private void markAccountFailure(AccountEntity account, Exception ex) {
        int failures = account.getConsecutiveFailures() == null
                ? 1
                : account.getConsecutiveFailures() + 1;
        OffsetDateTime now = OffsetDateTime.now();

        account.setLastCollectStatus(COLLECT_STATUS_FAILED);
        account.setLastCollectError(truncate(ex.getMessage(), MAX_ERROR_LENGTH));
        account.setConsecutiveFailures(failures);
        account.setNextCollectAt(now.plusMinutes(Math.min(60, failures * 5L)));
        accountRepository.save(account);
    }

    private BigDecimal decimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private boolean different(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left != right;
        }
        return left.compareTo(right) != 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化采集数据失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("计算采集数据哈希失败", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}