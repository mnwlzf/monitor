package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.newapi.NewApiAdapter;
import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokensResponse;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * New API 账号采集。
 *
 * <p>余额走 {@code /api/user/self}（一次请求同时产出余额与用量看板），
 * 分组走 {@code /api/user/self/groups}，密钥走 {@code /api/token/} 分页加每个密钥的明文接口。</p>
 */
@Component
public class NewApiAccountCollector implements AccountCollector {

    private static final Logger log = LoggerFactory.getLogger(NewApiAccountCollector.class);

    /** 额度统一展示单位。 */
    private static final String QUOTA_UNIT_USD = "USD";
    /** New API 密钥列表每页条数。 */
    private static final int NEW_API_TOKEN_PAGE_SIZE = 100;
    /** New API 密钥列表最大翻页数，避免上游分页异常导致死循环。 */
    private static final int NEW_API_TOKEN_MAX_PAGES = 100;

    private final AccountCredentialService credentialService;
    private final AccountSnapshotWriter snapshots;
    private final UpstreamGroupSync groupSync;
    private final AccountApiKeySync apiKeySync;
    private final NewApiAdapter newApiAdapter;
    private final CollectionJson json;

    public NewApiAccountCollector(AccountCredentialService credentialService,
                                  AccountSnapshotWriter snapshots,
                                  UpstreamGroupSync groupSync,
                                  AccountApiKeySync apiKeySync,
                                  NewApiAdapter newApiAdapter,
                                  CollectionJson json) {
        this.credentialService = credentialService;
        this.snapshots = snapshots;
        this.groupSync = groupSync;
        this.apiKeySync = apiKeySync;
        this.newApiAdapter = newApiAdapter;
        this.json = json;
    }

    @Override
    public String platformType() {
        return CollectionPlatforms.NEW_API;
    }

    /**
     * 按范围采集：余额、分组、API Key 各自独立，避免重复请求上游。
     */
    @Override
    public void collect(AccountEntity account, PlatformEntity platform, Long collectionRunId, CollectionScope scope) {
        if (scope.includesBalance()) {
            collectBalance(account, platform, collectionRunId);
        }
        if (scope.includesGroups()) {
            collectGroups(account, platform, collectionRunId);
        }
        if (scope.includesApiKeys()) {
            collectApiKeys(account, platform, collectionRunId);
        }
    }

    /**
     * 余额采集：{@code /api/user/self} 同时返回余额与用量看板，一次请求写两张快照。
     */
    private void collectBalance(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API 余额采集开始: accountId={}, baseUrl={}, username={}",
                account.getId(), baseUrl, username);

        NewApiSelfResponse selfResponse = newApiAdapter.fetchSelf(baseUrl, username, password);
        snapshots.saveNewApiMetric(account, collectionRunId, selfResponse);
        snapshots.saveNewApiUsageDashboard(account, collectionRunId, selfResponse);
        log.info("New API 余额采集成功: accountId={}, userId={}, quota={}, usedQuota={}",
                account.getId(), selfResponse.data().id(), selfResponse.data().quota(), selfResponse.data().usedQuota());
        if (selfResponse.data().id() != null) {
            account.setExternalUserId(String.valueOf(selfResponse.data().id()));
        }
    }

    /**
     * 分组/渠道倍率采集：{@code /api/user/self/groups}。
     */
    private void collectGroups(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API 分组采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        NewApiGroupsResponse groupsResponse = newApiAdapter.fetchGroups(baseUrl, username, password);
        syncGroups(account, platform.getPlatformType(), collectionRunId, groupsResponse);
    }

    /**
     * API Key 采集：{@code /api/token/}（分页）+ 每个 Key 的明文接口。
     */
    private void collectApiKeys(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API API Key 采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        syncApiKeys(account, platform.getPlatformType(), collectionRunId, baseUrl, username, password);
    }

    /** 分组同步：映射成统一快照后交给 {@link UpstreamGroupSync}。 */
    private void syncGroups(AccountEntity account, String platformType,
                            Long collectionRunId, NewApiGroupsResponse response) {
        if (response.data() == null) {
            return;
        }
        List<UpstreamGroupSync.GroupSnapshot> groups = new ArrayList<>();
        for (Map.Entry<String, NewApiGroupsResponse.Group> entry : response.data().entrySet()) {
            String externalGroupId = entry.getKey();
            NewApiGroupsResponse.Group group = entry.getValue();
            groups.add(new UpstreamGroupSync.GroupSnapshot(
                    externalGroupId,
                    externalGroupId,
                    group.desc(),
                    null,
                    CollectionValues.decimal(group.ratio()),
                    CollectionValues.decimal(group.baseRatio()),
                    Boolean.TRUE.equals(group.scheduleActive()) ? "SCHEDULE_ACTIVE" : "ACTIVE",
                    json.toJson(group)));
        }
        groupSync.sync(account, platformType, collectionRunId, groups, "New API");
    }

    /**
     * 同步密钥：分页拉全量列表、按 id 获取完整明文并加密存储，最后做增量对比。
     */
    private void syncApiKeys(AccountEntity account, String platformType, Long collectionRunId,
                             String baseUrl, String username, String password) {
        List<NewApiTokensResponse.Item> items = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE;
        while (page <= NEW_API_TOKEN_MAX_PAGES && items.size() < total) {
            NewApiTokensResponse response = newApiAdapter.fetchTokens(
                    baseUrl, username, password, page, NEW_API_TOKEN_PAGE_SIZE);
            NewApiTokensResponse.Data data = response.data();
            if (data == null || data.items() == null || data.items().isEmpty()) {
                break;
            }
            items.addAll(data.items());
            total = data.total() == null ? items.size() : data.total();
            page++;
        }

        Map<String, AccountApiKeyEntity> existing = apiKeySync.activeApiKeyMap(account.getId());
        // 「使用中」= 被本平台号池账号绑定；一次查完，后面逐条判定不再打库
        Set<Long> inUseKeyIds = apiKeySync.inUseKeyIds(existing.values());
        Set<String> activeExternalIds = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        int added = 0;
        int updated = 0;

        for (NewApiTokensResponse.Item item : items) {
            if (item.id() == null) {
                continue;
            }
            String externalKeyId = String.valueOf(item.id());
            activeExternalIds.add(externalKeyId);

            AccountApiKeyEntity entity = existing.get(externalKeyId);
            boolean isNew = entity == null;
            if (isNew) {
                entity = apiKeySync.newApiKey(account, platformType, externalKeyId, now);
            }

            boolean rotated = apiKeySync.applyFullKey(entity, isNew,
                    fetchFullKey(baseUrl, username, password, item.id()));

            String status = normalizeTokenStatus(item.status());
            BigDecimal remainQuota = CollectionValues.newApiUsd(item.remainQuota());
            BigDecimal usedQuota = CollectionValues.newApiUsd(item.usedQuota());
            boolean changed = isNew || rotated;
            if (!isNew) {
                changed = apiKeySync.recordApiKeyChanges(account, platformType, collectionRunId, entity,
                        item.name(), status, item.group(), inUseKeyIds) || changed;
            }

            entity.setKeyName(item.name());
            entity.setKeyMasked(item.key());
            entity.setStatus(status);
            entity.setUpstreamStatus(item.status() == null ? null : String.valueOf(item.status()));
            entity.setGroupName(item.group());
            entity.setUnlimitedQuota(item.unlimitedQuota());
            entity.setRemainQuota(remainQuota);
            entity.setUsedQuota(usedQuota);
            entity.setQuotaUnit(QUOTA_UNIT_USD);
            entity.setModelLimitsEnabled(item.modelLimitsEnabled());
            entity.setModelLimits(item.modelLimits());
            entity.setAllowIps(item.allowIps());
            entity.setExpiresAt(toOffsetDateTime(item.expiredTime()));
            entity.setUpstreamCreatedAt(toOffsetDateTime(item.createdTime()));
            entity.setLastUsedAt(toOffsetDateTime(item.accessedTime()));
            entity.setIsActive(true);
            entity.setLastSeenAt(now);
            entity.setMetrics(json.toJson(keyMetrics(item)));
            entity.setRawData(json.toRedactedJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeySync.save(entity);

            if (isNew) {
                added++;
                recordKeyChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.API_KEY_ADDED, null, entity.getRawData(),
                        "密钥新增: " + apiKeySync.displayKeyName(entity), inUseKeyIds);
            } else if (rotated) {
                updated++;
                recordKeyChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.API_KEY_ROTATED, "key", null,
                        "密钥已轮换: " + apiKeySync.displayKeyName(entity), inUseKeyIds);
            } else if (changed) {
                updated++;
            }

            apiKeySync.saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = apiKeySync.deactivateMissingApiKeys(account, platformType, collectionRunId,
                existing, activeExternalIds, now, inUseKeyIds);
        log.info("New API 密钥同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), items.size(), added, updated, removed);
    }

    private void recordKeyChange(AccountEntity account, String platformType, Long collectionRunId,
                                 AccountApiKeyEntity entity, String changeType,
                                 String fieldName, Object newValue, String message,
                                 Set<Long> inUseKeyIds) {
        // 这里走 changeRecorder 的统一入口，保持与其它事件一致（含脱敏与使用中判定）
        apiKeySync.recordKeyChange(account, platformType, collectionRunId, entity, changeType,
                fieldName, newValue, message, inUseKeyIds);
    }

    /**
     * 获取 New API 完整明文密钥；失败时返回 null，保留上一轮密文，不影响采集主流程。
     */
    private String fetchFullKey(String baseUrl, String username, String password, Long tokenId) {
        try {
            return newApiAdapter.fetchTokenKey(baseUrl, username, password, tokenId);
        } catch (Exception ex) {
            log.warn("获取 New API 完整密钥失败，跳过本轮加密: baseUrl={}, tokenId={}, error={}",
                    baseUrl, tokenId, ex.getMessage());
            return null;
        }
    }

    /** New API 状态码归一化。 */
    private String normalizeTokenStatus(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 1 -> "ACTIVE";
            case 2 -> "DISABLED";
            case 3 -> "EXPIRED";
            case 4 -> "EXHAUSTED";
            default -> "UNKNOWN";
        };
    }

    /**
     * Unix 秒转 OffsetDateTime；-1 或非正数表示无该时间（如永不过期）。
     */
    private OffsetDateTime toOffsetDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    /** New API 密钥特有明细。额度保留上游原始 quota，quota_per_usd 供前端换算。 */
    private Map<String, Object> keyMetrics(NewApiTokensResponse.Item item) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("user_id", item.userId());
        metrics.put("remain_quota_raw", item.remainQuota());
        metrics.put("used_quota_raw", item.usedQuota());
        metrics.put("quota_per_usd", CollectionValues.newApiQuotaPerUsd());
        metrics.put("cross_group_retry", item.crossGroupRetry());
        metrics.put("group_route_config", item.groupRouteConfig());
        metrics.put("group_route_sticky", item.groupRouteSticky());
        return metrics;
    }
}
