package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountApiKeySnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.UpstreamGroupSnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountApiKeySnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupSnapshotEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final String ENTITY_TYPE_API_KEY = "API_KEY";
    private static final String CHANGE_API_KEY_ADDED = "API_KEY_ADDED";
    private static final String CHANGE_API_KEY_REMOVED = "API_KEY_REMOVED";
    private static final String CHANGE_API_KEY_UPDATED = "API_KEY_UPDATED";
    private static final String CHANGE_API_KEY_ROTATED = "API_KEY_ROTATED";
    /** 额度统一展示单位。 */
    private static final String QUOTA_UNIT_USD = "USD";
    /** New API 密钥列表每页条数。 */
    private static final int NEW_API_TOKEN_PAGE_SIZE = 100;
    /** New API 密钥列表最大翻页数，避免上游分页异常导致死循环。 */
    private static final int NEW_API_TOKEN_MAX_PAGES = 100;
    /** 采集成功后的默认下次采集间隔。 */
    private static final Duration DEFAULT_COLLECT_INTERVAL = Duration.ofMinutes(10);
    /** New API 中 1 USD 对应的内部额度数量。 */
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
    private final AccountApiKeyRepository apiKeyRepository;
    private final AccountApiKeySnapshotRepository apiKeySnapshotRepository;
    private final CredentialCipher credentialCipher;
    private final NewApiAdapter newApiAdapter;
    private final Sub2ApiAdapter sub2ApiAdapter;
    private final ObjectMapper objectMapper;
    private final Executor collectionTaskExecutor;

    public CollectionService(AccountRepository accountRepository,
                             PlatformRepository platformRepository,
                             AccountCredentialService credentialService,
                             CollectionRunRepository collectionRunRepository,
                             AccountMetricSnapshotRepository metricSnapshotRepository,
                             AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                             UpstreamGroupRepository groupRepository,
                             UpstreamGroupSnapshotRepository groupSnapshotRepository,
                             UpstreamChangeEventRepository changeEventRepository,
                             AccountApiKeyRepository apiKeyRepository,
                             AccountApiKeySnapshotRepository apiKeySnapshotRepository,
                             CredentialCipher credentialCipher,
                             NewApiAdapter newApiAdapter,
                             Sub2ApiAdapter sub2ApiAdapter,
                             ObjectMapper objectMapper,
                              @Qualifier("collectionTaskExecutor") Executor collectionTaskExecutor) {
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
        this.credentialService = credentialService;
        this.collectionRunRepository = collectionRunRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.groupRepository = groupRepository;
        this.groupSnapshotRepository = groupSnapshotRepository;
        this.changeEventRepository = changeEventRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeySnapshotRepository = apiKeySnapshotRepository;
        this.credentialCipher = credentialCipher;
        this.newApiAdapter = newApiAdapter;
        this.sub2ApiAdapter = sub2ApiAdapter;
        this.objectMapper = objectMapper;
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
            if (PLATFORM_NEW_API.equalsIgnoreCase(platformType)) {
                collectNewApi(account, platform, run.getId(), effectiveScope);
            } else if (PLATFORM_SUB2_API.equalsIgnoreCase(platformType)) {
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
                    truncate(ex.getMessage(), MAX_ERROR_LENGTH)
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
        saveNewApiMetric(account, collectionRunId, selfResponse);
        saveNewApiUsageDashboard(account, collectionRunId, selfResponse);
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
     * Sub2API 余额采集：{@code /api/v1/auth/me} 取余额，{@code /api/v1/usage/dashboard/stats} 取用量看板。
     */
    private void collectSub2ApiBalance(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API 余额采集开始: accountId={}, baseUrl={}, email={}",
                account.getId(), baseUrl, email);

        Sub2ProfileResponse profileResponse = sub2ApiAdapter.fetchProfile(baseUrl, email, password);
        if (profileResponse.data() == null) {
            throw new IllegalStateException("Sub2API 个人信息响应缺少 data");
        }
        saveSub2Metric(account, collectionRunId, profileResponse);
        log.info("Sub2API 余额采集成功: accountId={}, userId={}, balance={}, frozenBalance={}",
                account.getId(), profileResponse.data().id(),
                profileResponse.data().balance(), profileResponse.data().frozenBalance());
        if (profileResponse.data().id() != null) {
            account.setExternalUserId(String.valueOf(profileResponse.data().id()));
        }

        Sub2UsageDashboardResponse usageResponse =
                sub2ApiAdapter.fetchUsageDashboardStats(baseUrl, email, password);
        saveSub2UsageDashboard(account, collectionRunId, profileResponse, usageResponse);
    }

    /**
     * Sub2API 分组/渠道倍率采集：{@code /api/v1/groups/available}。
     */
    private void collectSub2ApiGroups(AccountEntity account, PlatformEntity platform, Long collectionRunId) {
        String password = credentialService.resolvePassword(account.getId());
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
        String password = credentialService.resolvePassword(account.getId());
        String email = account.getEmail();
        String baseUrl = platform.getUrl();
        log.info("Sub2API API Key 采集开始: accountId={}, baseUrl={}", account.getId(), baseUrl);

        syncSub2ApiApiKeys(account, platform.getPlatformType(), collectionRunId, baseUrl, email, password);
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

    /**
     * 查询账号当前有效分组，并以外部分组 ID 建立索引，便于本轮采集做增量对比。
     */
    private Map<String, UpstreamGroupEntity> activeGroupMap(Integer accountId) {
        Map<String, UpstreamGroupEntity> result = new HashMap<>();
        for (UpstreamGroupEntity group : groupRepository.findByAccount(accountId, true)) {
            result.put(group.getExternalGroupId(), group);
        }
        return result;
    }

    /**
     * 创建新发现的分组实体，并初始化首次发现和最后可见时间。
     */
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

    /**
     * 将本轮上游未返回的分组标记为下线，并记录下线事件和快照。
     *
     * @return 本轮下线的分组数量
     */
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

    /**
     * 对比分组本轮数据与上一轮状态，逐字段写入变更事件。
     *
     * @return 是否至少发生一项变更
     */
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
    /**
     * 保存一次分组状态快照，用于历史查询和变更追溯。
     */
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

        Map<String, AccountApiKeyEntity> existing = activeApiKeyMap(account.getId());
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
                entity = newApiKey(account, platformType, externalKeyId, now);
            }

            boolean rotated = applyFullKey(entity, isNew,
                    fetchNewApiFullKey(baseUrl, username, password, item.id()));

            String status = normalizeNewApiTokenStatus(item.status());
            BigDecimal remainQuota = newApiUsd(item.remainQuota());
            BigDecimal usedQuota = newApiUsd(item.usedQuota());
            boolean changed = isNew || rotated;
            if (!isNew) {
                changed = recordApiKeyChanges(account, platformType, collectionRunId, entity,
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
            entity.setMetrics(toJson(newApiKeyMetrics(item)));
            entity.setRawData(toJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeyRepository.save(entity);

            if (isNew) {
                added++;
                recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_ADDED,
                        null, null, entity.getRawData(), "密钥新增: " + displayKeyName(entity));
            } else if (rotated) {
                updated++;
                recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_ROTATED,
                        "key", null, null, "密钥已轮换: " + displayKeyName(entity));
            } else if (changed) {
                updated++;
            }

            saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = deactivateMissingApiKeys(account, platformType, collectionRunId, existing, activeExternalIds, now);
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
        Map<String, AccountApiKeyEntity> existing = activeApiKeyMap(account.getId());
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
                entity = newApiKey(account, platformType, externalKeyId, now);
            }

            String fullKey = item.key();
            boolean rotated = applyFullKey(entity, isNew,
                    fullKey == null || fullKey.isBlank() || fullKey.contains("*") ? null : fullKey);

            String status = normalizeSub2KeyStatus(item.status());
            boolean changed = isNew || rotated;
            if (!isNew) {
                changed = recordApiKeyChanges(account, platformType, collectionRunId, entity,
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
            entity.setMetrics(toJson(sub2KeyMetrics(item, usage)));
            entity.setRawData(toJson(item));
            if (changed) {
                entity.setLastChangedAt(now);
            }
            apiKeyRepository.save(entity);

            if (isNew) {
                added++;
                recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_ADDED,
                        null, null, entity.getRawData(), "密钥新增: " + displayKeyName(entity));
            } else if (rotated) {
                updated++;
                recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_ROTATED,
                        "key", null, null, "密钥已轮换: " + displayKeyName(entity));
            } else if (changed) {
                updated++;
            }

            saveApiKeySnapshot(entity, collectionRunId, now);
        }

        int removed = deactivateMissingApiKeys(account, platformType, collectionRunId, existing, activeExternalIds, now);
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
     * 加密并写入完整密钥；哈希变化时判定为轮换。
     *
     * @return 密钥是否发生轮换
     */
    private boolean applyFullKey(AccountApiKeyEntity entity, boolean isNew, String fullKey) {
        if (fullKey == null || fullKey.isBlank()) {
            return false;
        }
        String hash = sha256(fullKey);
        boolean rotated = !isNew && entity.getKeyHash() != null && !entity.getKeyHash().equals(hash);
        if (isNew || rotated || entity.getKeyHash() == null) {
            CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(fullKey);
            entity.setKeyEncryptionAlgorithm(encrypted.algorithm());
            entity.setKeyEncryptedPayload(encrypted.encryptedPayload());
            entity.setKeyInitializationVector(encrypted.initializationVector());
            entity.setKeyKeyVersion(encrypted.keyVersion());
            entity.setKeyHash(hash);
        }
        return rotated;
    }

    /**
     * 查询账号当前有效密钥，并以上游密钥 ID 建立索引，便于本轮采集做增量对比。
     */
    private Map<String, AccountApiKeyEntity> activeApiKeyMap(Integer accountId) {
        Map<String, AccountApiKeyEntity> result = new HashMap<>();
        for (AccountApiKeyEntity key : apiKeyRepository.findByAccount(accountId, true)) {
            result.put(key.getExternalKeyId(), key);
        }
        return result;
    }

    /**
     * 创建新发现的密钥实体，并初始化首次发现和最后可见时间。
     */
    private AccountApiKeyEntity newApiKey(AccountEntity account, String platformType,
                                          String externalKeyId, OffsetDateTime now) {
        AccountApiKeyEntity entity = new AccountApiKeyEntity();
        entity.setAccountId(account.getId());
        entity.setPlatformType(platformType);
        entity.setExternalKeyId(externalKeyId);
        entity.setIsActive(true);
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setLastChangedAt(now);
        return entity;
    }

    /**
     * 对比密钥名称、状态和所属分组，逐字段写入变更事件；额度类指标不参与比较。
     *
     * @return 是否至少发生一项变更
     */
    private boolean recordApiKeyChanges(AccountEntity account, String platformType, Long collectionRunId,
                                        AccountApiKeyEntity entity, String keyName, String status,
                                        String groupName) {
        boolean changed = false;
        if (!Objects.equals(entity.getKeyName(), keyName)) {
            // 名称本身就是变化项，用明确标注的上游 ID 定位密钥，避免旧名被误读成新名。
            recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_UPDATED,
                    "name", entity.getKeyName(), keyName, "密钥名称变化（密钥 ID " + entity.getExternalKeyId() + "）");
            changed = true;
        }
        if (!Objects.equals(entity.getStatus(), status)) {
            recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_UPDATED,
                    "status", entity.getStatus(), status, "密钥状态变化: " + displayKeyName(entity));
            changed = true;
        }
        if (!Objects.equals(entity.getGroupName(), groupName)) {
            recordApiKeyChange(account, platformType, collectionRunId, entity, CHANGE_API_KEY_UPDATED,
                    "group", entity.getGroupName(), groupName, "密钥所属分组变化: " + displayKeyName(entity));
            changed = true;
        }
        return changed;
    }

    /**
     * 将本轮上游未返回的密钥标记为失效，并记录事件与快照。
     *
     * @return 本轮失效的密钥数量
     */
    private int deactivateMissingApiKeys(AccountEntity account, String platformType, Long collectionRunId,
                                         Map<String, AccountApiKeyEntity> existing,
                                         Set<String> activeExternalIds, OffsetDateTime now) {
        int removed = 0;
        for (AccountApiKeyEntity key : existing.values()) {
            if (activeExternalIds.contains(key.getExternalKeyId())) {
                continue;
            }
            key.setIsActive(false);
            key.setLastChangedAt(now);
            apiKeyRepository.save(key);
            recordApiKeyChange(account, platformType, collectionRunId, key, CHANGE_API_KEY_REMOVED,
                    "is_active", true, false, "密钥已失效: " + displayKeyName(key));
            saveApiKeySnapshot(key, collectionRunId, now);
            removed++;
        }
        return removed;
    }

    /**
     * 保存一次密钥状态快照，用于历史查询。
     */
    private void saveApiKeySnapshot(AccountApiKeyEntity entity, Long collectionRunId, OffsetDateTime now) {
        AccountApiKeySnapshotEntity snapshot = new AccountApiKeySnapshotEntity();
        snapshot.setApiKeyId(entity.getId());
        snapshot.setAccountId(entity.getAccountId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(entity.getPlatformType());
        snapshot.setExternalKeyId(entity.getExternalKeyId());
        snapshot.setKeyName(entity.getKeyName());
        snapshot.setStatus(entity.getStatus());
        snapshot.setGroupName(entity.getGroupName());
        snapshot.setUnlimitedQuota(entity.getUnlimitedQuota());
        snapshot.setRemainQuota(entity.getRemainQuota());
        snapshot.setUsedQuota(entity.getUsedQuota());
        snapshot.setQuotaUnit(entity.getQuotaUnit());
        snapshot.setMetrics(entity.getMetrics());
        snapshot.setRawData(entity.getRawData());
        snapshot.setCollectedAt(now);
        apiKeySnapshotRepository.save(snapshot);
    }

    /**
     * 写入 API Key 变更事件。
     */
    private void recordApiKeyChange(AccountEntity account, String platformType, Long collectionRunId,
                                    AccountApiKeyEntity entity, String changeType,
                                    String fieldName, Object oldValue, Object newValue,
                                    String message) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setAccountId(account.getId());
        event.setPlatformType(platformType);
        event.setCollectionRunId(collectionRunId);
        event.setEntityType(ENTITY_TYPE_API_KEY);
        event.setEntityId(entity.getId());
        event.setEntityKey(entity.getExternalKeyId());
        event.setChangeType(changeType);
        event.setFieldName(fieldName);
        event.setOldValue(toJson(oldValue));
        event.setNewValue(toJson(newValue));
        event.setSeverity("INFO");
        event.setMessage(message);
        event.setDetectedAt(OffsetDateTime.now());
        event.setMetadata("{}");
        log.info("记录密钥变更事件: accountId={}, runId={}, platformType={}, changeType={}, field={}, keyId={}",
                account.getId(), collectionRunId, platformType, changeType, fieldName, entity.getExternalKeyId());
        changeEventRepository.save(event);
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
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.contains("*") || key.length() <= 12) {
            return key;
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 变更说明里的密钥标识：优先名称、其次脱敏值，并统一附上明确标注的上游 ID。
     *
     * <p>只写裸 ID 时无法判断这串数字代表什么，这里始终带上「密钥 ID」前缀，
     * 保证变更记录自解释、无歧义。</p>
     */
    private String displayKeyName(AccountApiKeyEntity entity) {
        String identity = "密钥 ID " + entity.getExternalKeyId();
        if (StrUtil.isNotEmpty(entity.getKeyName())) {
            return entity.getKeyName() + "（" + identity + "）";
        }
        if (StrUtil.isNotEmpty(entity.getKeyMasked())) {
            return entity.getKeyMasked() + "（" + identity + "）";
        }
        return identity;
    }

    /**
     * New API 密钥特有明细。额度保留上游原始 quota，quota_per_usd 供前端换算。
     */
    private Map<String, Object> newApiKeyMetrics(NewApiTokensResponse.Item item) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("user_id", item.userId());
        metrics.put("remain_quota_raw", item.remainQuota());
        metrics.put("used_quota_raw", item.usedQuota());
        metrics.put("quota_per_usd", NEW_API_QUOTA_PER_USD);
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
        account.setLastCollectError(truncate(ex.getMessage(), MAX_ERROR_LENGTH));
        account.setConsecutiveFailures(failures);
        account.setNextCollectAt(now.plusMinutes(Math.min(60, failures * 5L)));
        accountRepository.save(account);
    }

    /**
     * 将 Long 型上游额度安全转换为 BigDecimal。
     */
    private BigDecimal decimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * 将 Double 型上游金额安全转换为 BigDecimal。
     */
    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * 使用数值比较判断两个 BigDecimal 是否不同，避免 1.0 与 1.00 被误判。
     */
    private boolean different(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left != right;
        }
        return left.compareTo(right) != 0;
    }

    /**
     * 将对象序列化为 JSON，用于保存原始响应或变更前后值。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化采集数据失败", ex);
        }
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要，用于判断原始数据是否变化。
     */
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

    /**
     * 截断过长的错误信息，避免超过数据库字段长度。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
