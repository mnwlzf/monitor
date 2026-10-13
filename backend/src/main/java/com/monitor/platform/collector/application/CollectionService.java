package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.CollectionRunRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游采集编排。
 *
 * <p>只负责「调度、批次、账号状态」：把到期账号并发提交、记录采集批次、维护失败退避；
 * 具体每个平台怎么取数、字段怎么映射，由 {@link AccountCollector} 的实现承担
 * （New API / Sub2API 各一个）。这样新增上游平台只加一个实现类，不必再动采集主流程。</p>
 *
 * <p>网络调用不包在长事务中，持久化由各 Repository 独立完成。</p>
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private static final String COLLECT_STATUS_SUCCESS = "SUCCESS";
    private static final String COLLECT_STATUS_FAILED = "FAILED";
    private static final String CREDENTIAL_STATUS_VALID = "VALID";
    /** 采集成功后的默认下次采集间隔。 */
    private static final Duration DEFAULT_COLLECT_INTERVAL = Duration.ofMinutes(10);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final AccountRepository accountRepository;
    private final PlatformRepository platformRepository;
    private final CollectionRunRepository collectionRunRepository;
    /** 平台类型（小写）-> 采集器。 */
    private final Map<String, AccountCollector> collectors;
    private final Executor collectionTaskExecutor;

    public CollectionService(AccountRepository accountRepository,
                             PlatformRepository platformRepository,
                             CollectionRunRepository collectionRunRepository,
                             List<AccountCollector> collectors,
                              @Qualifier("collectionTaskExecutor") Executor collectionTaskExecutor) {
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
        this.collectionRunRepository = collectionRunRepository;
        this.collectionTaskExecutor = collectionTaskExecutor;
        Map<String, AccountCollector> byType = new HashMap<>();
        for (AccountCollector collector : collectors) {
            byType.put(collector.platformType().toLowerCase(Locale.ROOT), collector);
        }
        this.collectors = byType;
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
            AccountCollector collector = collectors.get(
                    platformType == null ? "" : platformType.toLowerCase(Locale.ROOT));
            if (collector == null) {
                throw new IllegalArgumentException("不支持的平台类型: " + platformType);
            }
            collector.collect(account, platform, run.getId(), effectiveScope);

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
