package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.AccountApiKeyResponse;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.dto.UpstreamDashboardResponse;
import com.monitor.platform.api.dto.UpstreamGroupResponse;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录后的初始化数据聚合服务。
 *
 * <p>一次读取平台、账号及关联明细，避免浏览器按平台逐个请求；账号指标、渠道、
 * 密钥、变更和用量均使用批量查询，查询次数不随平台数增长。</p>
 */
@Service
public class UpstreamDashboardService {

    /** 初始化时最多返回的变更事件数。 */
    private static final int DASHBOARD_CHANGE_LIMIT = 500;

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
        List<PlatformResponse> platforms = adminService.listPlatforms();
        List<AccountEntity> accountEntities = accountRepository.findAll();
        List<Integer> accountIds = accountEntities.stream().map(AccountEntity::getId).toList();

        List<AccountResponse> accounts = adminService.toAccountResponses(accountEntities);
        List<UpstreamGroupResponse> groups = adminService.listGroups(accountIds);
        List<UpstreamChangeEventResponse> changes = usageService.listChanges(accountIds, DASHBOARD_CHANGE_LIMIT);
        List<AccountUsageDashboardResponse> usageDashboards = usageService.listUsageDashboard(accountEntities);
        List<AccountApiKeyResponse> apiKeys = adminService.listApiKeys(accountEntities);

        return new UpstreamDashboardResponse(
                platforms, accounts, groups, changes, usageDashboards, apiKeys);
    }
}
