package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.pool.client.Sub2AdminAccount;
import com.monitor.platform.pool.client.Sub2AdminAccountPage;
import com.monitor.platform.pool.client.Sub2AdminClient;
import com.monitor.platform.pool.client.Sub2UsageLog;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 号池数据同步服务。
 *
 * <p>只调用 Sub2API 管理员只读接口：健康度任务同步号池账号列表与状态，
 * 明细任务增量拉取逐请求数据并落地到本地，供任意时间粒度聚合。</p>
 */
@Service
public class PoolSyncService {

    private static final Logger log = LoggerFactory.getLogger(PoolSyncService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 号池账号列表每页条数。 */
    private static final int ACCOUNT_PAGE_SIZE = 100;
    /** 号池账号列表最大翻页数，避免上游分页异常导致死循环。 */
    private static final int ACCOUNT_MAX_PAGES = 50;
    /** 逐请求明细每页条数。 */
    private static final int SAMPLE_PAGE_SIZE = 1000;
    /** 逐请求明细单个账号单轮最大翻页数（每页 1000 条），避免异常账号拖垮整轮采集。 */
    private static final int SAMPLE_MAX_PAGES = 5;
    /**
     * 单轮明细采集的总时长预算。
     *
     * <p>号池账号较多时，一轮可能跑很久，导致任务长时间占用上游接口并「卡顿」。
     * 超过预算就提前结束本轮，剩余账号留到下一轮继续（水位已推进，下一轮只取增量）。</p>
     */
    private static final Duration SAMPLE_BUDGET = Duration.ofMinutes(8);
    /** 自动绑定凭证导出的最小间隔：导出全量明文凭证很慢，避免每次健康检查都触发。 */
    private static final Duration BIND_MIN_INTERVAL = Duration.ofMinutes(30);
    /**
     * 首次采集时最多回溯的天数。
     *
     * <p>上游 /admin/usage 只支持按天过滤，且一次采集可能返回上千条明细。
     * 这里明确「最多往前 7 天」，不做全量回溯，避免首次采集长时间卡住。</p>
     */
    private static final int LOOKBACK_DAYS = 7;
    private static final int MAX_ERROR_LENGTH = 500;

    private final PlatformRepository platformRepository;
    private final PoolAccountRepository poolAccountRepository;
    private final PoolSampleRepository poolSampleRepository;
    private final Sub2AdminClient adminClient;
    private final PoolAdminKeyResolver adminKeyResolver;
    private final PoolAccountBindService bindService;
    /** 各平台最近一次自动绑定时间，内存态节流，重启后重置。 */
    private final Map<Integer, Instant> lastBindAttemptAt = new ConcurrentHashMap<>();

    public PoolSyncService(PlatformRepository platformRepository,
                           PoolAccountRepository poolAccountRepository,
                           PoolSampleRepository poolSampleRepository,
                           Sub2AdminClient adminClient,
                           PoolAdminKeyResolver adminKeyResolver,
                           PoolAccountBindService bindService) {
        this.platformRepository = platformRepository;
        this.poolAccountRepository = poolAccountRepository;
        this.poolSampleRepository = poolSampleRepository;
        this.adminClient = adminClient;
        this.adminKeyResolver = adminKeyResolver;
        this.bindService = bindService;
    }

    /**
     * 同步所有启用 Sub2API 平台的号池账号健康状态。
     */
    public void syncAllHealth() {
        int success = 0;
        int skipped = 0;
        int failed = 0;
        for (PlatformEntity platform : platformRepository.findEnabled()) {
            // 只有被显式标记为「号池监控源」的平台才需要管理员密钥；
            // 其它 Sub2API / New API 只是它的上游，不参与号池监控。
            if (!isPoolSource(platform)) {
                continue;
            }
            if (!platform.hasAdminKey()) {
                skipped++;
                continue;
            }
            try {
                syncHealth(platform);
                success++;
            } catch (Exception ex) {
                failed++;
                log.error("号池健康度同步失败: platformId={}, name={}",
                        platform.getId(), platform.getPlatformName(), ex);
            }
        }
        log.info("号池健康度同步完成: success={}, skipped={}, failed={}", success, skipped, failed);
    }

    /**
     * 同步单个平台的号池账号健康状态，并尝试自动绑定本地密钥。
     */
    public void syncHealth(PlatformEntity platform) {
        String adminKey = requireAdminKey(platform);
        List<Sub2AdminAccount> accounts = fetchAllAccounts(platform.getUrl(), adminKey);
        OffsetDateTime now = OffsetDateTime.now();
        int synced = 0;
        for (Sub2AdminAccount account : accounts) {
            // 只监控 API Key 类型的号池账号，OAuth 等其他类型一律忽略。
            if (account.id() == null || !PoolAccountTypes.isApiKey(account.accountType())) {
                continue;
            }
            poolAccountRepository.upsert(toEntity(platform.getId(), account, now));
            synced++;
        }
        int purged = purgeNonApiKeyAccounts(platform.getId());
        int bound = autoBindIfNeeded(platform, adminKey);
        log.info("号池健康度同步完成: platformId={}, apiKeyAccounts={}, purgedOtherTypes={}, boundCount={}",
                platform.getId(), synced, purged, bound);
    }

    /**
     * 清理历史遗留的非 API Key 号池账号及其明细。
     *
     * <p>只删除「类型明确且不是 apikey」的记录；类型为空（上游未返回）时保守保留，
     * 避免解析异常导致误删。因此这里不依赖本轮拉取结果，天然不受分页中断影响。</p>
     */
    private int purgeNonApiKeyAccounts(Integer platformId) {
        List<Long> stale = new ArrayList<>();
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            String normalized = PoolAccountTypes.normalize(account.getAccountType());
            if (normalized != null && !PoolAccountTypes.API_KEY.equals(normalized)) {
                stale.add(account.getExternalAccountId());
                if (log.isDebugEnabled()) {
                    log.debug("待清理号池账号: externalAccountId={}, accountType={}, name={}",
                            account.getExternalAccountId(), account.getAccountType(), account.getName());
                }
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }
        poolSampleRepository.deleteByExternalIds(platformId, stale);
        int deleted = poolAccountRepository.deleteByExternalIds(platformId, stale);
        log.info("已清理非 API Key 类型号池账号: platformId={}, count={}", platformId, deleted);
        return deleted;
    }

    /**
     * 按平台 ID 同步号池健康状态。
     */
    public void syncHealth(Integer platformId) {
        PlatformEntity platform = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        requirePoolSource(platform);
        syncHealth(platform);
    }

    /**
     * 增量采集所有启用 Sub2API 平台的号池逐请求明细。
     */
    public void syncAllSamples() {
        int success = 0;
        int skipped = 0;
        int failed = 0;
        for (PlatformEntity platform : platformRepository.findEnabled()) {
            // 只有被显式标记为「号池监控源」的平台才需要管理员密钥；
            // 其它 Sub2API / New API 只是它的上游，不参与号池监控。
            if (!isPoolSource(platform)) {
                continue;
            }
            if (!platform.hasAdminKey()) {
                skipped++;
                continue;
            }
            try {
                syncSamples(platform);
                success++;
            } catch (Exception ex) {
                failed++;
                log.error("号池明细采集失败: platformId={}, name={}",
                        platform.getId(), platform.getPlatformName(), ex);
            }
        }
        log.info("号池明细采集完成: success={}, skipped={}, failed={}", success, skipped, failed);
    }

    /**
     * 增量采集单个平台的号池逐请求明细。
     */
    public void syncSamples(PlatformEntity platform) {
        String adminKey = requireAdminKey(platform);
        List<PoolAccountEntity> accounts = poolAccountRepository.findByPlatform(platform.getId());
        LocalDate today = LocalDate.now(ZONE);
        Instant deadline = Instant.now().plus(SAMPLE_BUDGET);
        int totalInserted = 0;
        int collected = 0;
        int failed = 0;
        for (PoolAccountEntity account : accounts) {
            if (Instant.now().isAfter(deadline)) {
                log.info("号池明细采集达到单轮时长预算 {}，本轮提前结束，剩余 {} 个账号留到下一轮",
                        SAMPLE_BUDGET, accounts.size() - collected - failed);
                break;
            }
            // pool_accounts 里已经只保留 API Key 类型账号，这里逐个增量拉取用量明细。
            // 指标采集不依赖「本地密钥绑定」：绑定只用于展示这个号池账号对应本项目的哪个 Key。
            try {
                totalInserted += syncAccountSamples(platform, adminKey, account, today);
                poolAccountRepository.updateSyncError(platform.getId(), account.getExternalAccountId(), null);
                collected++;
            } catch (Exception ex) {
                failed++;
                poolAccountRepository.updateSyncError(platform.getId(), account.getExternalAccountId(),
                        truncate(ex.getMessage(), MAX_ERROR_LENGTH));
                log.warn("号池账号明细采集失败: platformId={}, externalAccountId={}, reason={}",
                        platform.getId(), account.getExternalAccountId(), ex.getMessage());
            }
        }
        log.info("号池明细采集完成: platformId={}, accountCount={}, collected={}, failed={}, insertedSamples={}",
                platform.getId(), accounts.size(), collected, failed, totalInserted);
    }

    /**
     * 按平台 ID 增量采集号池明细。
     */
    public void syncSamples(Integer platformId) {
        PlatformEntity platform = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        requirePoolSource(platform);
        syncSamples(platform);
    }

    private int syncAccountSamples(PlatformEntity platform, String adminKey,
                                   PoolAccountEntity account, LocalDate today) {
        OffsetDateTime watermark = account.getLastSampleAt();
        LocalDate start = watermark == null
                ? today.minusDays(LOOKBACK_DAYS)
                : watermark.atZoneSameInstant(ZONE).toLocalDate().minusDays(1);
        if (start.isAfter(today)) {
            start = today;
        }

        List<Sub2UsageLog> logs = fetchAllUsage(platform.getUrl(), adminKey,
                account.getExternalAccountId(), start, today);
        List<PoolSampleEntity> entities = new ArrayList<>(logs.size());
        OffsetDateTime maxCreatedAt = watermark;
        for (Sub2UsageLog entry : logs) {
            if (entry.requestId() == null || entry.createdAt() == null) {
                continue;
            }
            entities.add(toEntity(platform.getId(), account.getExternalAccountId(), entry));
            if (maxCreatedAt == null || entry.createdAt().isAfter(maxCreatedAt)) {
                maxCreatedAt = entry.createdAt();
            }
        }

        int inserted = poolSampleRepository.insertBatch(entities);
        if (maxCreatedAt != null && !maxCreatedAt.equals(watermark)) {
            poolAccountRepository.updateSampleWatermark(platform.getId(), account.getExternalAccountId(), maxCreatedAt);
        }
        return inserted;
    }

    /**
     * 翻完上游号池账号的全部页。
     *
     * <p>上游响应带 {@code total}/{@code pages}，直接按 {@code pages} 结束，
     * 比「本页条数小于页大小」更可靠（避免上游忽略分页参数时无限翻页）。</p>
     */
    private List<Sub2AdminAccount> fetchAllAccounts(String baseUrl, String adminKey) {
        List<Sub2AdminAccount> all = new ArrayList<>();
        int page = 1;
        while (page <= ACCOUNT_MAX_PAGES) {
            Sub2AdminAccountPage result = adminClient.fetchAccounts(baseUrl, adminKey, page, ACCOUNT_PAGE_SIZE);
            log.info("号池账号列表已拉取: page={}/{}, total={}, size={}",
                    result.page(), result.pages(), result.total(), result.items().size());
            if (result.items().isEmpty()) {
                break;
            }
            all.addAll(result.items());
            if (result.pages() <= 0 || page >= result.pages()) {
                break;
            }
            page++;
        }
        return all;
    }

    /**
     * 存在未绑定号池账号时尝试自动绑定，并按平台做时间节流。
     *
     * <p>自动绑定依赖 {@code /admin/accounts/data} 导出全量明文凭证，响应体很大。
     * 若某账号本来就匹配不到本地密钥，放任每次健康检查都导出会白白拖慢任务，
     * 因此这里限制同一平台最多每 {@link #BIND_MIN_INTERVAL} 尝试一次。</p>
     */
    private int autoBindIfNeeded(PlatformEntity platform, String adminKey) {
        if (!hasUnboundAccount(platform.getId())) {
            return 0;
        }
        Instant now = Instant.now();
        Instant last = lastBindAttemptAt.get(platform.getId());
        if (last != null && now.isBefore(last.plus(BIND_MIN_INTERVAL))) {
            log.debug("距上次自动绑定不足 {}，跳过: platformId={}", BIND_MIN_INTERVAL, platform.getId());
            return 0;
        }
        lastBindAttemptAt.put(platform.getId(), now);
        return bindService.autoBind(platform.getId(), platform.getUrl(), adminKey);
    }

    /** 平台下是否存在尚未绑定本地密钥的号池账号。 */
    private boolean hasUnboundAccount(Integer platformId) {
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            if (account.getBoundKeyId() == null) {
                return true;
            }
        }
        return false;
    }

    private List<Sub2UsageLog> fetchAllUsage(String baseUrl, String adminKey, Long accountId,
                                             LocalDate start, LocalDate end) {
        List<Sub2UsageLog> all = new ArrayList<>();
        for (int page = 1; page <= SAMPLE_MAX_PAGES; page++) {
            List<Sub2UsageLog> batch = adminClient.fetchUsage(baseUrl, adminKey, accountId,
                    start, end, page, SAMPLE_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < SAMPLE_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    private PoolAccountEntity toEntity(Integer platformId, Sub2AdminAccount account, OffsetDateTime now) {
        PoolAccountEntity entity = new PoolAccountEntity();
        entity.setPlatformId(platformId);
        entity.setExternalAccountId(account.id());
        entity.setName(account.name());
        entity.setPlatform(account.platform());
        entity.setAccountType(account.accountType());
        entity.setStatus(account.status());
        entity.setSchedulable(account.schedulable() == null ? Boolean.TRUE : account.schedulable());
        entity.setErrorMessage(truncate(account.errorMessage(), MAX_ERROR_LENGTH));
        entity.setRateLimitedAt(account.rateLimitedAt());
        entity.setRateLimitResetAt(account.rateLimitResetAt());
        entity.setOverloadUntil(account.overloadUntil());
        entity.setTempUnschedulableUntil(account.tempUnschedulableUntil());
        entity.setTempUnschedulableReason(account.tempUnschedulableReason());
        entity.setConcurrency(account.concurrency());
        entity.setPriority(account.priority());
        entity.setRateMultiplier(account.rateMultiplier());
        entity.setLastUsedAt(account.lastUsedAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private PoolSampleEntity toEntity(Integer platformId, Long externalAccountId, Sub2UsageLog logEntry) {
        PoolSampleEntity entity = new PoolSampleEntity();
        entity.setPlatformId(platformId);
        entity.setExternalAccountId(externalAccountId);
        entity.setRequestId(logEntry.requestId());
        entity.setApiKeyId(logEntry.apiKeyId());
        entity.setModel(logEntry.model());
        entity.setChannelId(logEntry.channelId());
        entity.setEndpoint(logEntry.endpoint());
        entity.setStream(logEntry.stream());
        entity.setCreatedAt(logEntry.createdAt());
        entity.setFirstTokenMs(logEntry.firstTokenMs());
        entity.setDurationMs(logEntry.durationMs());
        entity.setInputTokens(logEntry.inputTokens());
        entity.setOutputTokens(logEntry.outputTokens());
        entity.setCacheReadTokens(logEntry.cacheReadTokens());
        entity.setCacheCreationTokens(logEntry.cacheCreationTokens());
        entity.setTotalCost(logEntry.totalCost());
        entity.setActualCost(logEntry.actualCost());
        entity.setIngestedAt(OffsetDateTime.now());
        return entity;
    }

    /** 是否为号池监控源（用户自建的 Sub2API）。 */
    private boolean isPoolSource(PlatformEntity platform) {
        return Boolean.TRUE.equals(platform.getPoolMonitoringEnabled());
    }

    /** 校验平台已被标记为号池监控源。 */
    private void requirePoolSource(PlatformEntity platform) {
        if (!isPoolSource(platform)) {
            throw BusinessException.of("该平台不是号池监控源，无法执行号池采集: " + platform.getPlatformName());
        }
    }

    private String requireAdminKey(PlatformEntity platform) {
        return adminKeyResolver.resolve(platform)
                .orElseThrow(() -> BusinessException.of(
                        "平台未配置可用的 Sub2API 管理员密钥: " + platform.getPlatformName()));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}