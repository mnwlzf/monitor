package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.adapter.newapi.NewApiAdapter;
import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import com.monitor.platform.adapter.newapi.model.NewApiTokensResponse;
import com.monitor.platform.adapter.newapi.model.NewApiUser;
import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2ApiKeysUsageResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2ProfileResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2UsageDashboardResponse;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.util.SecretMasker;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游采集业务服务。
 *
 * <p>负责账号调度、凭证解析、适配器调用、指标快照、渠道状态同步和变更事件记录。
 * 网络调用不包在长事务中，持久化由各 Repository 独立完成。</p>
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private static final String COLLECT_STATUS_RUNNING = "RUNNING";
    private static final String COLLECT_STATUS_SUCCESS = "SUCCESS";
    private static final String COLLECT_STATUS_FAILED = "FAILED";
    private static final String CREDENTIAL_STATUS_VALID = "VALID";

    /** 密钥归一化状态：启用（正在使用）。 */
    private static final String KEY_STATUS_ACTIVE = "ACTIVE";
    /** 额度统一展示单位。 */
    private static final String QUOTA_UNIT_USD = "USD";
    /** New API 密钥列表每页条数。 */
    private static final int NEW_API_TOKEN_PAGE_SIZE = 100;
    /** New API 密钥列表最大翻页数，避免上游分页异常导致死循环。 */
    private static final int NEW_API_TOKEN_MAX_PAGES = 100;
    /** 采集成功后的默认下次采集间隔。 */
    private static final Duration DEFAULT_COLLECT_INTERVAL = Duration.ofMinutes(10);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final AccountRepository accountRepository;
    private final PlatformRepository platformRepository;
    private final AccountCredentialService credentialService;
    private final CollectionRunRepository collectionRunRepository;
    private final AccountSnapshotWriter snapshots;
    private final UpstreamGroupSync groupSync;
    private final AccountApiKeySync apiKeySync;
    private final NewApiAdapter newApiAdapter;
    private final Sub2ApiAdapter sub2ApiAdapter;
    private final CollectionJson json;
    private final UpstreamChangeRecorder changeRecorder;
    private final Executor collectionTaskExecutor;

    public CollectionService(AccountRepository accountRepository,
                             PlatformRepository platformRepository,
                             AccountCredentialService credentialService,
                             CollectionRunRepository collectionRunRepository,
                             AccountSnapshotWriter snapshots,
                             UpstreamGroupSync groupSync,
                             AccountApiKeySync apiKeySync,
                             NewApiAdapter newApiAdapter,
                             Sub2ApiAdapter sub2ApiAdapter,
                             CollectionJson json,
                             UpstreamChangeRecorder changeRecorder,
                              @Qualifier("collectionTaskExecutor") Executor collectionTaskExecutor) {
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
        this.credentialService = credentialService;
        this.collectionRunRepository = collectionRunRepository;
        this.snapshots = snapshots;
        this.groupSync = groupSync;
        this.apiKeySync = apiKeySync;
        this.newApiAdapter = newApiAdapter;
        this.sub2ApiAdapter = sub2ApiAdapter;
        this.json = json;
        this.changeRecorder = changeRecorder;
        this.collectionTaskExecutor = collectionTaskExecutor;
    }

    /**
     * 采集一批到期账号。
     *
     * <p>该方法是批量采集入口，可由外部调度器周期性调用；单个账号失败会被捕获，
     * 不会阻断同一平台或其他平台后续账号的采集。</p>
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
                    // 单个账号失败只记录日志，继续处理本平台剩余账号。
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
     * 并发采集所有启用平台下的全部启用账号（全量：余额 + 分组 + API Key）。
     */
    public void collectAllAccounts() {
        collectAllAccounts(CollectionScope.FULL);
    }

    /**
     * 按指定范围并发采集所有启用平台下的全部启用账号。
     *
     * <p>每轮把所有启用账号提交到 {@code collectionTaskExecutor} 并发执行；单个账号失败只记录日志，
     * 不影响其他账号。不同范围对应上游互不重叠的接口请求集合，拆成独立任务后可分别控制频率。</p>
     *
     * @param scope 采集范围，为空时按全量处理
     */
    public void collectAllAccounts(CollectionScope scope) {
        CollectionScope effectiveScope = scope == null ? CollectionScope.FULL : scope;
        List<AccountEntity> accounts = new ArrayList<>();
        for (PlatformEntity platform : platformRepository.findEnabled()) {
            List<AccountEntity> platformAccounts = accountRepository.findEnabledByPlatformId(platform.getId());
            accounts.addAll(platformAccounts);
            log.info("平台账号收集完成: platformId={}, platformName={}, accountCount={}",
                    platform.getId(), platform.getPlatformName(), platformAccounts.size());
        }
        if (accounts.isEmpty()) {
            log.info("没有需要采集的账号: scope={}", effectiveScope);
            return;
        }

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicReference<String> firstError = new AtomicReference<>();
        log.info("开始并发采集全部账号: scope={}, accountCount={}", effectiveScope, accounts.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (AccountEntity account : accounts) {
            futures.add(CompletableFuture.runAsync(
                    () -> collectSafely(account, effectiveScope, successCount, failureCount, firstError),
                    collectionTaskExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int success = successCount.get();
        int failure = failureCount.get();
        log.info("全部账号并发采集结束: scope={}, total={}, success={}, failure={}",
                effectiveScope, accounts.size(), success, failure);

        if (failure > 0) {
            // 抛出让定时任务框架把状态标记为 FAILED，并把汇总错误展示到任务页面
            throw new IllegalStateException(String.format(
                    "共 %d 个账号，成功 %d，失败 %d；首个错误：%s",
                    accounts.size(), success, failure, firstError.get()));
        }
    }

    /**
     * 捕获单账号采集异常，保证并发任务不会因个别账号失败而中断；
     * 同时记录首个失败原因，供任务页面展示。
     */
    private void collectSafely(AccountEntity account, CollectionScope scope,
                               AtomicInteger successCount, AtomicInteger failureCount,
                               AtomicReference<String> firstError) {
        try {
            collectAccount(null, account.getId(), scope);
            successCount.incrementAndGet();
        } catch (Exception ex) {
            failureCount.incrementAndGet();
            String display = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
            if (StrUtil.isBlank(display)) {
                display = "账号 " + account.getId();
            }
            firstError.compareAndSet(null, display + "：" + rootMessage(ex));
            log.error("账号并发采集失败: accountId={}, scope={}", account.getId(), scope, ex);
        }
    }

    /**
     * 取最深层原因的可读信息，避免只看到「账号采集失败: 1」这类外层包装。
     */
    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StrUtil.isBlank(message) ? current.getClass().getSimpleName() : message;
    }

    /**
     * 采集单个账号（全量：余额 + 分组 + API Key），并写入指标、渠道、快照和变更事件。
     */
    public void collectAccount(Integer accountId) {
        collectAccount(null, accountId);
    }

    /**
     * 采集指定平台下的单个账号（全量）。
     */
    public void collectAccount(Integer platformId, Integer accountId) {
        collectAccount(platformId, accountId, CollectionScope.FULL);
    }

    /**
     * 按指定范围采集单个账号。
     *
     * <p>只有包含余额的范围（BALANCE、FULL）才会更新账号的最近采集时间与健康状态，
     * 分组、API Key 任务只写各自的批次记录，避免互相覆盖账号状态。</p>
     */
    public void collectAccount(Integer platformId, Integer accountId, CollectionScope scope) {
        CollectionScope effectiveScope = scope == null ? CollectionScope.FULL : scope;
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: " + accountId));
        if (platformId != null && !platformId.equals(account.getPlatformId())) {
            throw new IllegalArgumentException("账号不属于指定平台: " + accountId);
        }
        PlatformEntity platform = platformRepository.findById(account.getPlatformId())
                .orElseThrow(() -> new IllegalArgumentException("账号未关联平台: " + accountId));

        // 停用的平台或账号不参与采集；定时任务已过滤，这里兜底手动采集
        if (Boolean.FALSE.equals(platform.getStatus())) {
            throw BusinessException.of("平台已停用，账号不参与采集: " + platform.getPlatformName());
        }
        if (Boolean.FALSE.equals(account.getStatus())) {
            throw BusinessException.of("账号已停用，不参与采集: " + accountId);
        }

        String platformType = platform.getPlatformType();
        log.info("账号采集开始: accountId={}, platformId={}, platformType={}, scope={}, account={}",
                accountId, platform.getId(), platformType, effectiveScope,
                StrUtil.blankToDefault(account.getUsername(), account.getEmail()));

        CollectionRunEntity run = collectionRunRepository.start(accountId, platformType, effectiveScope.name(), "{}");
        log.info("采集批次已创建: accountId={}, runId={}, scope={}", accountId, run.getId(), effectiveScope);
        long startedMillis = System.currentTimeMillis();

        try {
            if (CollectionPlatforms.NEW_API.equalsIgnoreCase(platformType)) {
                collectNewApi(account, platform, run.getId(), effectiveScope);
            } else if (CollectionPlatforms.SUB2_API.equalsIgnoreCase(platformType)) {
                collectSub2Api(account, platform, run.getId(), effectiveScope);
            } else {
                throw new IllegalArgumentException("不支持的平台类型: " + platformType);
            }

            if (effectiveScope.includesBalance()) {
                markAccountSuccess(account);
            }
            long durationMs = System.currentTimeMillis() - startedMillis;
            collectionRunRepository.finish(
                    run.getId(),
                    COLLECT_STATUS_SUCCESS,
                    OffsetDateTime.now(),
                    durationMs,
                    null,
                    null
            );
            log.info("账号采集成功: accountId={}, runId={}, scope={}, durationMs={}",
                    accountId, run.getId(), effectiveScope, durationMs);
        } catch (Exception ex) {
            if (effectiveScope.includesBalance()) {
                markAccountFailure(account, ex);
            }
            long durationMs = System.currentTimeMillis() - startedMillis;
            collectionRunRepository.finish(
                    run.getId(),
                    COLLECT_STATUS_FAILED,
                    OffsetDateTime.now(),
                    durationMs,
                    "COLLECTION_FAILED",
                    CollectionValues.truncate(ex.getMessage(), MAX_ERROR_LENGTH)
            );
            log.error("账号采集失败: accountId={}, runId={}, scope={}, durationMs={}",
                    accountId, run.getId(), effectiveScope, durationMs, ex);
            throw new IllegalStateException("账号采集失败: " + accountId, ex);
        }
    }

    /**
     * 按范围采集 New API 账号：余额、分组、API Key 各自独立，避免重复请求上游。
     */
    private void collectNewApi(AccountEntity account, PlatformEntity platform, Long collectionRunId,
                               CollectionScope scope) {
        if (scope.includesBalance()) {
            collectNewApiBalance(account, platform, collectionRunId);
        }
        if (scope.includesGroups()) {
            collectNewApiGroups(account, platform, collectionRunId);
        }
        if (scope.includesApiKeys()) {
            collectNewApiApiKeys(account, platform, collectionRunId);
        }
    }

    /**
     * New API 余额采集：{@code /api/user/self} 同时返回余额与用量看板，一次请求写两张快照。
     */
    private void collectNewApiBalance(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
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
     * New API 分组/渠道倍率采集：{@code /api/user/self/groups}。
     */
    private void collectNewApiGroups(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API 分组采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        NewApiGroupsResponse groupsResponse = newApiAdapter.fetchGroups(baseUrl, username, password);
        syncNewApiGroups(account, platform.getPlatformType(), collectionRunId, groupsResponse);
    }

    /**
     * New API API Key 采集：{@code /api/token/}（分页）+ 每个 Key 的明文接口。
     */
    private void collectNewApiApiKeys(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String username = StrUtil.blankToDefault(account.getUsername(), account.getEmail());
        String baseUrl = platform.getUrl();
        log.info("New API API Key 采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        syncNewApiApiKeys(account, platform.getPlatformType(), collectionRunId, baseUrl, username, password);
    }

    /**
     * 按范围采集 Sub2API 账号：余额（含用量看板）、分组、API Key 各自独立。
     */
    private void collectSub2Api(AccountEntity account, PlatformEntity platform, Long collectionRunId,
                                CollectionScope scope) {
        if (scope.includesBalance()) {
            collectSub2ApiBalance(account, platform, collectionRunId);
        }
        if (scope.includesGroups()) {
            collectSub2ApiGroups(account, platform, collectionRunId);
        }
        if (scope.includesApiKeys()) {
            collectSub2ApiApiKeys(account, platform, collectionRunId);
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
    private String resolveSub2Password(AccountEntity account, PlatformEntity platform) {
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
     * Sub2API 余额采集：{@code /api/v1/auth/me} 取余额，{@code /api/v1/usage/dashboard/stats} 取用量看板。
     */
    private void collectSub2ApiBalance(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolveSub2Password(account, platform);
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
     * Sub2API 分组/渠道倍率采集：{@code /api/v1/groups/available}。
     */
    private void collectSub2ApiGroups(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolveSub2Password(account, platform);
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API 分组采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        Sub2GroupsResponse groupsResponse = sub2ApiAdapter.fetchAvailableGroups(baseUrl, email, password);
        syncSub2Groups(account, platform.getPlatformType(), collectionRunId, groupsResponse);
    }

    /**
     * Sub2API API Key 采集：{@code /api/v1/keys} + {@code /api/v1/usage/dashboard/api-keys-usage}。
     */
    private void collectSub2ApiApiKeys(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = resolveSub2Password(account, platform);
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API API Key 采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        syncSub2ApiApiKeys(account, platform.getPlatformType(), collectionRunId, baseUrl, email, password);
    }

    /** New API 分组/渠道倍率同步：映射成统一快照后交给 {@link UpstreamGroupSync}。 */
    private void syncNewApiGroups(AccountEntity account, String platformType,
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

    /** Sub2API 分组/渠道倍率同步：映射成统一快照后交给 {@link UpstreamGroupSync}。 */
    private void syncSub2Groups(AccountEntity account, String platformType,
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
     * 同步 New API 密钥：分页拉全量列表、按 id 获取完整明文并加密存储，最后做增量对比。
     */
    private void syncNewApiApiKeys(AccountEntity account, String platformType, Long collectionRunId,
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
                    fetchNewApiFullKey(baseUrl, username, password, item.id()));

            String status = normalizeNewApiTokenStatus(item.status());
            BigDecimal remainQuota = CollectionValues.newApiUsd(item.remainQuota());
            BigDecimal usedQuota = CollectionValues.newApiUsd(item.usedQuota());
            boolean changed = isNew || rotated;
            if (!isNew) {
                changed = apiKeySync.recordApiKeyChanges(account, platformType, collectionRunId, entity,
                        item.name(), status, item.group()) || changed;
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
            entity.setMetrics(json.toJson(newApiKeyMetrics(item)));
            entity.setRawData(json.toRedactedJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeySync.save(entity);

            if (isNew) {
                added++;
                changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity, UpstreamChangeTypes.API_KEY_ADDED,
                        null, null, entity.getRawData(), "密钥新增: " + apiKeySync.displayKeyName(entity),
                        apiKeySync.isKeyInUse(entity.getStatus()));
            } else if (rotated) {
                updated++;
                changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity, UpstreamChangeTypes.API_KEY_ROTATED,
                        "key", null, null, "密钥已轮换: " + apiKeySync.displayKeyName(entity),
                        apiKeySync.isKeyInUse(entity.getStatus()));
            } else if (changed) {
                updated++;
            }

            apiKeySync.saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = apiKeySync.deactivateMissingApiKeys(account, platformType, collectionRunId, existing, activeExternalIds, now);
        log.info("New API 密钥同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), items.size(), added, updated, removed);
    }

    /**
     * 同步 Sub2API 密钥：列表接口直接返回完整 key，加密后落库并做增量对比。
     */
    private void syncSub2ApiApiKeys(AccountEntity account, String platformType, Long collectionRunId,
                                    String baseUrl, String email, String password) {
        List<Sub2KeysResponse.KeyItem> items = sub2ApiAdapter.fetchAllKeys(baseUrl, email, password);
        Map<String, Sub2ApiKeysUsageResponse.Stat> usageStats = fetchSub2KeyUsage(baseUrl, email, password, items);
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

            String status = normalizeSub2KeyStatus(item.status());
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
            entity.setMetrics(json.toJson(sub2KeyMetrics(item, usage)));
            entity.setRawData(json.toRedactedJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeySync.save(entity);

            if (isNew) {
                added++;
                changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity, UpstreamChangeTypes.API_KEY_ADDED,
                        null, null, entity.getRawData(), "密钥新增: " + apiKeySync.displayKeyName(entity),
                        apiKeySync.isKeyInUse(entity.getStatus()));
            } else if (rotated) {
                updated++;
                changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity, UpstreamChangeTypes.API_KEY_ROTATED,
                        "key", null, null, "密钥已轮换: " + apiKeySync.displayKeyName(entity),
                        apiKeySync.isKeyInUse(entity.getStatus()));
            } else if (changed) {
                updated++;
            }

            apiKeySync.saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = apiKeySync.deactivateMissingApiKeys(account, platformType, collectionRunId, existing, activeExternalIds, now);
        log.info("Sub2API 密钥同步完成: accountId={}, total={}, added={}, updated={}, removed={}",
                account.getId(), items.size(), added, updated, removed);
    }

    /**
     * 获取 New API 完整明文密钥；失败时返回 null，保留上一轮密文，不影响采集主流程。
     */
    private String fetchNewApiFullKey(String baseUrl, String username, String password, Long tokenId) {
        try {
            return newApiAdapter.fetchTokenKey(baseUrl, username, password, tokenId);
        } catch (Exception ex) {
            log.warn("获取 New API 完整密钥失败，跳过本轮加密: baseUrl={}, tokenId={}, error={}",
                    baseUrl, tokenId, ex.getMessage());
            return null;
        }
    }

    /**
     * New API 状态码归一化。
     */
    private String normalizeNewApiTokenStatus(Integer status) {
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
     * Sub2API 状态归一化。
     */
    private String normalizeSub2KeyStatus(String status) {
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
     * Unix 秒转 OffsetDateTime；-1 或非正数表示无该时间（如永不过期）。
     */
    private OffsetDateTime toOffsetDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), java.time.ZoneOffset.UTC);
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
     * New API 密钥特有明细。额度保留上游原始 quota，quota_per_usd 供前端换算。
     */
    private Map<String, Object> newApiKeyMetrics(NewApiTokensResponse.Item item) {
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

    /**
     * 批量获取 Sub2API 密钥用量；失败时降级为空 Map，不阻断采集。
     */
    private Map<String, Sub2ApiKeysUsageResponse.Stat> fetchSub2KeyUsage(String baseUrl, String email,
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
    private Map<String, Object> sub2KeyMetrics(Sub2KeysResponse.KeyItem item,
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

    /**
     * 记录采集成功状态，并安排下一次常规采集时间。
     */
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

    /**
     * 记录采集失败状态，并按连续失败次数进行最长 60 分钟的退避。
     */
    private void markAccountFailure(AccountEntity account, Exception ex) {
        int failures = account.getConsecutiveFailures() == null
                ? 1
                : account.getConsecutiveFailures() + 1;
        OffsetDateTime now = OffsetDateTime.now();

        account.setLastCollectStatus(COLLECT_STATUS_FAILED);
        account.setLastCollectError(CollectionValues.truncate(ex.getMessage(), MAX_ERROR_LENGTH));
        account.setConsecutiveFailures(failures);
        account.setNextCollectAt(now.plusMinutes(Math.min(60, failures * 5L)));
        accountRepository.save(account);
    }
}
