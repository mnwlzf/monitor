package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2ApiKeysUsageResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sub2API 账号采集。
 *
 * <p>余额走 {@code /api/v1/auth/me} + {@code /api/v1/usage/dashboard/stats}，
 * 分组走 {@code /api/v1/groups/available}，
 * 密钥走 {@code /api/v1/keys} + {@code /api/v1/usage/dashboard/api-keys-usage}。</p>
 */
@Component
public class Sub2ApiAccountCollector implements AccountCollector {

    private static final Logger log = LoggerFactory.getLogger(Sub2ApiAccountCollector.class);

    /** 额度统一展示单位。 */
    private static final String QUOTA_UNIT_USD = "USD";

    private final AccountCredentialService credentialService;
    private final AccountSnapshotWriter snapshots;
    private final UpstreamGroupSync groupSync;
    private final AccountApiKeySync apiKeySync;
    private final Sub2ApiAdapter sub2ApiAdapter;
    private final CollectionJson json;

    public Sub2ApiAccountCollector(AccountCredentialService credentialService,
                                   AccountSnapshotWriter snapshots,
                                   UpstreamGroupSync groupSync,
                                   AccountApiKeySync apiKeySync,
                                   Sub2ApiAdapter sub2ApiAdapter,
                                   CollectionJson json) {
        this.credentialService = credentialService;
        this.snapshots = snapshots;
        this.groupSync = groupSync;
        this.apiKeySync = apiKeySync;
        this.sub2ApiAdapter = sub2ApiAdapter;
        this.json = json;
    }

    @Override
    public String platformType() {
        return CollectionPlatforms.SUB2_API;
    }

    /**
     * 按范围采集：余额（含用量看板）、分组、API Key 各自独立。
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
     * 解析 Sub2API 账号的登录凭据。
     *
     * <ul>
     *   <li>密码登录（PASSWORD）：返回解密后的密码；</li>
     *   <li>手动 Token（TOKEN）：把页面填写的 token 导入适配器缓存后返回 null，
     *       后续接口直接走 Bearer token；refresh_token 轮转后回写保存。</li>
     * </ul>
     */
    private String resolvePassword(AccountEntity account, PlatformEntity platform) {
        if (AccountCredentialService.TOKEN.equalsIgnoreCase(account.getAuthType())) {
            String refreshToken = credentialService
                    .resolveToken(account.getId(), AccountCredentialService.REFRESH_TOKEN)
                    .orElse(null);
            String accessToken = credentialService
                    .resolveToken(account.getId(), AccountCredentialService.ACCESS_TOKEN)
                    .orElse(null);

            String rotated = sub2ApiAdapter.importManualToken(
                    platform.getUrl(), account.getEmail(), accessToken, refreshToken);
            if (StrUtil.isNotBlank(rotated) && !rotated.equals(refreshToken)) {
                credentialService.saveToken(account.getId(), AccountCredentialService.REFRESH_TOKEN, rotated);
                log.info("Sub2API refresh_token 已轮转并回写: accountId={}", account.getId());
            }
            return null;
        }
        return credentialService.resolvePassword(account.getId());
    }

    /**
     * 余额采集：{@code /api/v1/auth/me} 取余额，{@code /api/v1/usage/dashboard/stats} 取用量看板。
     */
    private void collectBalance(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolvePassword(account, platform);
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API 余额采集开始: accountId={}, baseUrl={}, email={}",
                account.getId(), baseUrl, email);

        Sub2ProfileResponse profileResponse = sub2ApiAdapter.fetchProfile(baseUrl, email, password);
        if (profileResponse.data() == null) {
            throw new IllegalStateException("Sub2API 个人信息响应缺少 data");
        }
        snapshots.saveSub2Metric(account, collectionRunId, profileResponse);
        log.info("Sub2API 余额采集成功: accountId={}, userId={}, balance={}, frozenBalance={}",
                account.getId(), profileResponse.data().id(),
                profileResponse.data().balance(), profileResponse.data().frozenBalance());
        if (profileResponse.data().id() != null) {
            account.setExternalUserId(String.valueOf(profileResponse.data().id()));
        }

        Sub2UsageDashboardResponse usageResponse =
                sub2ApiAdapter.fetchUsageDashboardStats(baseUrl, email, password);
        snapshots.saveSub2UsageDashboard(account, collectionRunId, profileResponse, usageResponse);
    }

    /**
     * 分组/渠道倍率采集：{@code /api/v1/groups/available}。
     */
    private void collectGroups(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolvePassword(account, platform);
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API 分组采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        Sub2GroupsResponse groupsResponse = sub2ApiAdapter.fetchAvailableGroups(baseUrl, email, password);
        syncGroups(account, platform.getPlatformType(), collectionRunId, groupsResponse);
    }

    /**
     * API Key 采集：{@code /api/v1/keys} + {@code /api/v1/usage/dashboard/api-keys-usage}。
     */
    private void collectApiKeys(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolvePassword(account, platform);
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API API Key 采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        syncApiKeys(account, platform.getPlatformType(), collectionRunId, baseUrl, email, password);
    }

    /** 分组同步：映射成统一快照后交给 {@link UpstreamGroupSync}。 */
    private void syncGroups(AccountEntity account, String platformType,
                            Long collectionRunId, Sub2GroupsResponse response) {
        if (response.data() == null) {
            return;
        }
        List<UpstreamGroupSync.GroupSnapshot> groups = new ArrayList<>();
        for (Sub2GroupsResponse.Group group : response.data()) {
            groups.add(new UpstreamGroupSync.GroupSnapshot(
                    String.valueOf(group.id()),
                    group.name(),
                    group.description(),
                    group.platform(),
                    CollectionValues.decimal(group.rateMultiplier()),
                    null,
                    group.status(),
                    json.toJson(group)));
        }
        groupSync.sync(account, platformType, collectionRunId, groups, "Sub2API");
    }

    /**
     * 同步密钥：接口直接返回完整 key，加密后落库并做增量对比。
     */
    private void syncApiKeys(AccountEntity account, String platformType, Long collectionRunId,
                             String baseUrl, String email, String password) {
        List<Sub2KeysResponse.KeyItem> items = sub2ApiAdapter.fetchAllKeys(baseUrl, email, password);
        Map<String, Sub2ApiKeysUsageResponse.Stat> usageStats = fetchKeyUsage(baseUrl, email, password, items);
        Map<String, AccountApiKeyEntity> existing = apiKeySync.activeApiKeyMap(account.getId());
        Set<String> activeExternalIds = new HashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        int added = 0;
        int updated = 0;

        for (Sub2KeysResponse.KeyItem item : items) {
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

            String fullKey = item.key();
            boolean rotated = apiKeySync.applyFullKey(entity, isNew,
                    fullKey == null || fullKey.isBlank() || fullKey.contains("*") ? null : fullKey);

            String status = normalizeKeyStatus(item.status());
            boolean changed = isNew || rotated;
            if (!isNew) {
                changed = apiKeySync.recordApiKeyChanges(account, platformType, collectionRunId, entity,
                        item.name(), status, item.group() == null ? null : item.group().name()) || changed;
            }

            entity.setKeyName(item.name());
            entity.setKeyMasked(maskKey(fullKey));
            entity.setStatus(status);
            entity.setUpstreamStatus(item.status());
            entity.setGroupName(item.group() == null ? null : item.group().name());
            entity.setGroupPlatform(item.group() == null ? null : item.group().platform());
            Sub2ApiKeysUsageResponse.Stat usage = usageStats.get(externalKeyId);
            entity.setUnlimitedQuota(null);
            entity.setRemainQuota(null);
            // 列表 quota_used 恒为 0，只有批量用量接口才有真实值；取不到时留空而非记 0。
            entity.setUsedQuota(usage == null ? null : usage.totalActualCost());
            entity.setQuotaUnit(QUOTA_UNIT_USD);
            entity.setExpiresAt(parseIsoDateTime(item.expiresAt()));
            entity.setUpstreamCreatedAt(parseIsoDateTime(item.createdAt()));
            entity.setLastUsedAt(parseIsoDateTime(item.lastUsedAt()));
            entity.setIsActive(true);
            entity.setLastSeenAt(now);
            entity.setMetrics(json.toJson(keyMetrics(item, usage)));
            entity.setRawData(json.toRedactedJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeySync.save(entity);

            if (isNew) {
                added++;
                apiKeySync.recordKeyChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.API_KEY_ADDED, null, entity.getRawData(),
                        "密钥新增: " + apiKeySync.displayKeyName(entity));
            } else if (rotated) {
                updated++;
                apiKeySync.recordKeyChange(account, platformType, collectionRunId, entity,
                        UpstreamChangeTypes.API_KEY_ROTATED, "key", null,
                        "密钥已轮换: " + apiKeySync.displayKeyName(entity));
            } else if (changed) {
                updated++;
            }

            apiKeySync.saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = apiKeySync.deactivateMissingApiKeys(account, platformType, collectionRunId,
                existing, activeExternalIds, now);
        log.info("Sub2API 密钥同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), items.size(), added, updated, removed);
    }

    /** Sub2API 状态归一化。 */
    private String normalizeKeyStatus(String status) {
        if (StrUtil.isBlank(status)) {
            return "UNKNOWN";
        }
        return switch (status.trim().toLowerCase()) {
            case "active", "enabled", "1" -> "ACTIVE";
            case "disabled", "inactive", "0" -> "DISABLED";
            case "expired" -> "EXPIRED";
            case "exhausted" -> "EXHAUSTED";
            default -> status.trim().toUpperCase();
        };
    }

    /**
     * ISO-8601 时间字符串转 OffsetDateTime，解析失败返回 null。
     */
    private OffsetDateTime parseIsoDateTime(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ex) {
            log.debug("密钥时间字段解析失败，忽略: {}", value);
            return null;
        }
    }

    /**
     * 密钥脱敏展示：保留前 6 位与后 4 位。
     */
    private String maskKey(String key) {
        return SecretMasker.mask(key);
    }

    /**
     * 批量获取 Sub2API 密钥用量；失败时降级为空 Map，不阻断采集。
     */
    private Map<String, Sub2ApiKeysUsageResponse.Stat> fetchKeyUsage(String baseUrl, String email,
                                                                     String password,
                                                                     List<Sub2KeysResponse.KeyItem> items) {
        List<Long> ids = new ArrayList<>();
        for (Sub2KeysResponse.KeyItem item : items) {
            if (item.id() != null) {
                ids.add(item.id());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return sub2ApiAdapter.fetchKeysUsage(baseUrl, email, password, ids);
        } catch (Exception ex) {
            log.warn("获取 Sub2API 密钥用量失败，本轮仅记录密钥基础信息: baseUrl={}, error={}",
                    baseUrl, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Sub2API 密钥特有明细：真实用量、窗口用量、速率限制、并发与分组配置。
     */
    private Map<String, Object> keyMetrics(Sub2KeysResponse.KeyItem item,
                                           Sub2ApiKeysUsageResponse.Stat usage) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("quota", item.quota());
        metrics.put("quota_used", item.quotaUsed());
        if (usage != null) {
            metrics.put("today_actual_cost", usage.todayActualCost());
            metrics.put("total_actual_cost", usage.totalActualCost());
        }
        metrics.put("usage_5h", item.usage5h());
        metrics.put("usage_1d", item.usage1d());
        metrics.put("usage_7d", item.usage7d());
        metrics.put("rate_limit_5h", item.rateLimit5h());
        metrics.put("rate_limit_1d", item.rateLimit1d());
        metrics.put("rate_limit_7d", item.rateLimit7d());
        metrics.put("current_concurrency", item.currentConcurrency());
        metrics.put("window_5h_start", item.window5hStart());
        metrics.put("window_1d_start", item.window1dStart());
        metrics.put("window_7d_start", item.window7dStart());
        metrics.put("last_used_ip", item.lastUsedIp());
        metrics.put("ip_whitelist", item.ipWhitelist());
        metrics.put("ip_blacklist", item.ipBlacklist());
        if (item.group() != null) {
            metrics.put("group_id", item.group().id());
            metrics.put("group_rate_multiplier", item.group().rateMultiplier());
            metrics.put("daily_limit_usd", item.group().dailyLimitUsd());
            metrics.put("weekly_limit_usd", item.group().weeklyLimitUsd());
            metrics.put("monthly_limit_usd", item.group().monthlyLimitUsd());
        }
        return metrics;
    }
}
