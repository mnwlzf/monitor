package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.AccountApiKeyResponse;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.dto.UpstreamDashboardDetailsResponse;
import com.monitor.platform.api.dto.UpstreamDashboardResponse;
import com.monitor.platform.api.dto.UpstreamGroupResponse;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 登录后的初始化数据聚合服务。
 *
 * <p>一次读取平台、账号及关联明细，避免浏览器按平台逐个请求；账号指标、渠道、
 * 密钥、变更和用量均使用批量查询，查询次数不随平台数增长。</p>
 */
@Service
public class UpstreamDashboardService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamDashboardService.class);

    /** 初始化首屏只取少量变更，完整列表由后台明细接口补加载。 */
    private static final int DASHBOARD_CHANGE_LIMIT = 20;

    /** 后台明细接口返回的变更数量。 */
    private static final int DASHBOARD_DETAIL_CHANGE_LIMIT = 200;

    private final UpstreamAdminService adminService;
    private final UpstreamUsageService usageService;
    private final AccountRepository accountRepository;

    public UpstreamDashboardService(UpstreamAdminService adminService,
                                    UpstreamUsageService usageService,
                                    AccountRepository accountRepository) {
        this.adminService = adminService;
        this.usageService = usageService;
        this.accountRepository = accountRepository;
    }

    /**
     * 读取初始化所需的全部监控数据。
     */
    public UpstreamDashboardResponse loadDashboard() {
        long startedAt = System.nanoTime();
        List<PlatformResponse> platforms = adminService.listPlatforms();
        long platformsAt = System.nanoTime();
        List<AccountEntity> accountEntities = accountRepository.findAll();
        long accountsAt = System.nanoTime();
        List<Integer> accountIds = accountEntities.stream().map(AccountEntity::getId).toList();

        List<AccountResponse> accounts;
        List<UpstreamChangeEventResponse> changes;
        List<AccountUsageDashboardResponse> usageDashboards;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<AccountResponse>> accountsFuture = CompletableFuture.supplyAsync(
                    () -> adminService.toAccountResponses(accountEntities), executor);
            CompletableFuture<List<UpstreamChangeEventResponse>> changesFuture = CompletableFuture.supplyAsync(
                    () -> usageService.listChangesWithoutImpacts(accountIds, DASHBOARD_CHANGE_LIMIT), executor);
            CompletableFuture<List<AccountUsageDashboardResponse>> usageFuture = CompletableFuture.supplyAsync(
                    () -> usageService.listUsageDashboard(accountEntities), executor);

            accounts = accountsFuture.join();
            changes = changesFuture.join();
            usageDashboards = usageFuture.join();
        }

        long completedAt = System.nanoTime();
        log.info("dashboard core loaded: platforms={}ms accounts={}ms sections={}ms total={}ms",
                millis(startedAt, platformsAt), millis(platformsAt, accountsAt),
                millis(accountsAt, completedAt), millis(startedAt, completedAt));

        return new UpstreamDashboardResponse(
                platforms, accounts, List.of(), changes, usageDashboards, List.of());
    }

    /**
     * 后台补齐渠道、完整变更和 API Key；不阻塞首屏。
     */
    public UpstreamDashboardDetailsResponse loadDashboardDetails() {
        long startedAt = System.nanoTime();
        List<AccountEntity> accountEntities = accountRepository.findAll();
        List<Integer> accountIds = accountEntities.stream().map(AccountEntity::getId).toList();

        List<UpstreamGroupResponse> groups;
        List<UpstreamChangeEventResponse> changes;
        List<AccountApiKeyResponse> apiKeys;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<UpstreamGroupResponse>> groupsFuture = CompletableFuture.supplyAsync(
                    () -> adminService.listGroups(accountIds), executor);
            CompletableFuture<List<UpstreamChangeEventResponse>> changesFuture = CompletableFuture.supplyAsync(
                    () -> usageService.listChangesWithoutImpacts(accountIds, DASHBOARD_DETAIL_CHANGE_LIMIT), executor);
            CompletableFuture<List<AccountApiKeyResponse>> apiKeysFuture = CompletableFuture.supplyAsync(
                    () -> adminService.listApiKeys(accountEntities), executor);

            groups = groupsFuture.join();
            changes = changesFuture.join();
            apiKeys = apiKeysFuture.join();
        }

        log.info("dashboard details loaded: total={}ms", millis(startedAt, System.nanoTime()));
        return new UpstreamDashboardDetailsResponse(groups, changes, apiKeys);
    }

    private long millis(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
