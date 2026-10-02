package com.monitor.platform.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.CreateAccountRequest;
import com.monitor.platform.api.dto.CreatePlatformRequest;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.dto.UpstreamGroupResponse;
import com.monitor.platform.api.dto.UpdateAccountRequest;
import com.monitor.platform.api.dto.UpdatePlatformRequest;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.AccountUsageDashboardSnapshotRepository;
import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountUsageDashboardSnapshotEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游平台和账号管理服务。
 */
@Service
public class UpstreamAdminService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamAdminService.class);

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountMetricSnapshotRepository metricSnapshotRepository;
    private final AccountUsageDashboardSnapshotRepository usageDashboardRepository;
    private final UpstreamGroupRepository groupRepository;
    private final UpstreamChangeEventRepository changeEventRepository;
    private final AccountCredentialService credentialService;
    private final ObjectMapper objectMapper;

    public UpstreamAdminService(PlatformRepository platformRepository,
                                AccountRepository accountRepository,
                                AccountMetricSnapshotRepository metricSnapshotRepository,
                                AccountUsageDashboardSnapshotRepository usageDashboardRepository,
                                UpstreamGroupRepository groupRepository,
                                UpstreamChangeEventRepository changeEventRepository,
                                AccountCredentialService credentialService,
                                ObjectMapper objectMapper) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.usageDashboardRepository = usageDashboardRepository;
        this.groupRepository = groupRepository;
        this.changeEventRepository = changeEventRepository;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlatformResponse createPlatform(CreatePlatformRequest request) {
        platformRepository.findByName(request.name()).ifPresent(existing -> {
            throw BusinessException.of("平台名称已存在: " + request.name());
        });

        PlatformEntity entity = new PlatformEntity();
        entity.setPlatformName(request.name());
        entity.setUrl(request.baseUrl());
        entity.setPlatformType(request.platform());
        entity.setStatus(true);
        entity.setSettings("{}");
        platformRepository.save(entity);

        log.info("创建上游平台成功: platformId={}, name={}, type={}, baseUrl={}",
                entity.getId(), entity.getPlatformName(), entity.getPlatformType(), entity.getUrl());
        return toPlatformResponse(entity);
    }

    public List<PlatformResponse> listPlatforms() {
        return platformRepository.findEnabled().stream().map(this::toPlatformResponse).toList();
    }

    /**
     * 更新平台实例。平台类型变更后，其下所有账号会按新适配器采集。
     */
    @Transactional
    public PlatformResponse updatePlatform(Integer platformId, UpdatePlatformRequest request) {
        PlatformEntity entity = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        if (request.name() != null && !request.name().isBlank()) {
            platformRepository.findByName(request.name())
                    .filter(existing -> !existing.getId().equals(platformId))
                    .ifPresent(existing -> {
                        throw BusinessException.of("平台名称已存在: " + request.name());
                    });
            entity.setPlatformName(request.name());
        }
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            entity.setUrl(request.baseUrl());
        }
        if (request.platform() != null && !request.platform().isBlank()) {
            entity.setPlatformType(request.platform());
        }
        if (request.status() != null) {
            entity.setStatus(request.status());
        }

        platformRepository.save(entity);

        // 同步账号冗余的平台类型与 URL，避免采集仍读取旧值。
        for (AccountEntity account : accountRepository.findByPlatformId(platformId)) {
            account.setPlatform(entity.getPlatformType());
            account.setUrl(entity.getUrl());
            accountRepository.save(account);
        }

        log.info("更新上游平台成功: platformId={}, name={}, type={}, baseUrl={}",
                entity.getId(), entity.getPlatformName(), entity.getPlatformType(), entity.getUrl());
        return toPlatformResponse(entity);
    }

    /**
     * 删除平台实例（软删除）。平台下仍存在账号时拒绝删除，避免产生孤儿账号。
     */
    @Transactional
    public void deletePlatform(Integer platformId) {
        PlatformEntity entity = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        long accountCount = accountRepository.findByPlatformId(platformId).size();
        if (accountCount > 0) {
            throw BusinessException.of("平台下仍有 " + accountCount + " 个账号，请先删除账号再删除平台");
        }

        platformRepository.softDelete(platformId);
        log.info("删除上游平台成功: platformId={}, name={}", platformId, entity.getPlatformName());
    }

    @Transactional
    public AccountResponse createAccount(Integer platformId, CreateAccountRequest request) {
        PlatformEntity platform = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        accountRepository.findByPlatformIdAndEmail(platformId, request.loginName()).ifPresent(existing -> {
            throw BusinessException.of("该平台下账号已存在: " + request.loginName());
        });

        AccountEntity entity = new AccountEntity();
        entity.setPlatformId(platformId);
        entity.setPlatform(platform.getPlatformType());
        entity.setUrl(platform.getUrl());
        entity.setEmail(request.loginName());
        entity.setUsername(request.loginName());
        entity.setDisplayName(request.displayName());
        entity.setStatus(true);
        entity.setAuthType(request.authType() == null ? AccountCredentialService.PASSWORD : request.authType());
        entity.setCredentialStatus("UNKNOWN");
        entity.setConsecutiveFailures(0);
        entity.setSettings("{}");
        accountRepository.save(entity);

        credentialService.savePassword(entity.getId(), request.password());

        log.info("创建采集账号成功: accountId={}, platformId={}, loginName={}, authType={}",
                entity.getId(), platformId, request.loginName(), entity.getAuthType());
        return toAccountResponse(entity);
    }

    @Transactional
    public AccountResponse updateAccount(Integer platformId, Integer accountId,
                                         UpdateAccountRequest request) {
        AccountEntity entity = findAccount(platformId, accountId);

        if (request.loginName() != null && !request.loginName().isBlank()) {
            accountRepository.findByPlatformIdAndEmail(platformId, request.loginName())
                    .filter(existing -> !existing.getId().equals(accountId))
                    .ifPresent(existing -> {
                        throw BusinessException.of("该平台下账号已存在: " + request.loginName());
                    });
            entity.setEmail(request.loginName());
            entity.setUsername(request.loginName());
        }
        if (request.displayName() != null) {
            entity.setDisplayName(request.displayName());
        }
        if (request.authType() != null && !request.authType().isBlank()) {
            entity.setAuthType(request.authType());
        }

        accountRepository.save(entity);
        if (request.password() != null && !request.password().isBlank()) {
            credentialService.savePassword(accountId, request.password());
        }

        log.info("更新采集账号成功: accountId={}, platformId={}, loginName={}",
                accountId, platformId, entity.getUsername());
        return toAccountResponse(entity);
    }

    @Transactional
    public void deleteAccount(Integer platformId, Integer accountId) {
        AccountEntity entity = findAccount(platformId, accountId);
        accountRepository.softDelete(accountId);
        credentialService.deactivateAll(accountId);
        log.info("删除采集账号成功: accountId={}, platformId={}, loginName={}",
                accountId, platformId, entity.getUsername());
    }
    public List<AccountResponse> listAccounts(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        return accountRepository.findByPlatformId(platformId).stream().map(this::toAccountResponse).toList();
    }

    private AccountEntity findAccount(Integer platformId, Integer accountId) {
        AccountEntity entity = accountRepository.findById(accountId)
                .orElseThrow(() -> BusinessException.of("账号不存在: " + accountId));
        if (!platformId.equals(entity.getPlatformId())) {
            throw BusinessException.of("账号不属于指定平台: " + accountId);
        }
        return entity;
    }
    private UpstreamGroupResponse toGroupResponse(UpstreamGroupEntity entity) {
        return new UpstreamGroupResponse(
                entity.getId(), entity.getAccountId(), entity.getExternalGroupId(),
                entity.getGroupName(), entity.getDescription(), entity.getPlatform(),
                entity.getCurrentRatio(), entity.getCurrentBaseRatio(), entity.getStatus(),
                entity.getIsActive(), entity.getFirstSeenAt(), entity.getLastSeenAt(), entity.getLastChangedAt()
        );
    }

    private UpstreamChangeEventResponse toChangeEventResponse(UpstreamChangeEventEntity entity) {
        return new UpstreamChangeEventResponse(
                entity.getId(), entity.getAccountId(), entity.getPlatformType(),
                entity.getEntityType(), entity.getEntityKey(), entity.getChangeType(),
                entity.getFieldName(), entity.getOldValue(), entity.getNewValue(),
                entity.getSeverity(), entity.getMessage(), entity.getDetectedAt()
        );
    }

    private PlatformResponse toPlatformResponse(PlatformEntity entity) {
        return new PlatformResponse(
                entity.getId(),
                entity.getPlatformName(),
                entity.getUrl(),
                entity.getPlatformType(),
                entity.getStatus()
        );
    }

    public List<UpstreamGroupResponse> listGroups(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        List<UpstreamGroupResponse> result = new ArrayList<>();
        for (AccountEntity account : accountRepository.findByPlatformId(platformId)) {
            for (UpstreamGroupEntity group : groupRepository.findByAccount(account.getId(), false)) {
                result.add(toGroupResponse(group));
            }
        }
        return result;
    }

    public List<UpstreamChangeEventResponse> listChanges(Integer platformId, int limit) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<UpstreamChangeEventResponse> result = new ArrayList<>();
        for (AccountEntity account : accountRepository.findByPlatformId(platformId)) {
            for (UpstreamChangeEventEntity event : changeEventRepository.findRecent(account.getId(), safeLimit)) {
                result.add(toChangeEventResponse(event));
            }
        }
        result.sort(Comparator.comparing(UpstreamChangeEventResponse::detectedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result.stream().limit(safeLimit).toList();
    }

    /**
     * 查询平台下各账号的最新用量看板快照。
     *
     * <p>newapi 与 sub2api 使用同一张快照表，接口返回统一结构；
     * 平台未提供的指标为 null，由前端降级展示。</p>
     */
    public List<AccountUsageDashboardResponse> listUsageDashboard(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        List<AccountEntity> accounts = accountRepository.findByPlatformId(platformId);
        Map<Integer, AccountEntity> accountMap = new HashMap<>();
        for (AccountEntity account : accounts) {
            accountMap.put(account.getId(), account);
        }
        if (accountMap.isEmpty()) {
            return List.of();
        }

        List<AccountUsageDashboardResponse> result = new ArrayList<>();
        for (AccountUsageDashboardSnapshotEntity snapshot
                : usageDashboardRepository.findLatestByAccounts(new ArrayList<>(accountMap.keySet()))) {
            AccountEntity account = accountMap.get(snapshot.getAccountId());
            if (account == null) {
                continue;
            }
            result.add(new AccountUsageDashboardResponse(
                    snapshot.getId(),
                    snapshot.getAccountId(),
                    account.getPlatformId(),
                    account.getDisplayName(),
                    snapshot.getPlatformType(),
                    snapshot.getBalance(),
                    snapshot.getFrozenBalance(),
                    snapshot.getTotalRequests(),
                    snapshot.getTotalTokens(),
                    snapshot.getTotalCost(),
                    snapshot.getTotalActualCost(),
                    readJson(snapshot.getMetrics()),
                    readJson(snapshot.getPlatformStats()),
                    snapshot.getCollectedAt()
            ));
        }
        return result;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            log.warn("用量看板 JSON 字段解析失败，返回空值", ex);
            return null;
        }
    }

    private AccountResponse toAccountResponse(AccountEntity entity) {
        AccountMetricSnapshotEntity snapshot = metricSnapshotRepository.findLatest(entity.getId()).orElse(null);
        return new AccountResponse(
                entity.getId(),
                entity.getPlatformId(),
                entity.getDisplayName(),
                entity.getUsername(),
                entity.getPlatform(),
                entity.getCredentialStatus(),
                entity.getStatus(),
                snapshot == null ? null : snapshot.getBalance(),
                snapshot == null ? null : snapshot.getFrozenBalance(),
                snapshot == null ? null : snapshot.getQuota(),
                snapshot == null ? null : snapshot.getUsedQuota(),
                snapshot == null ? null : snapshot.getQuotaUnit(),
                snapshot == null ? null : snapshot.getRequestCount(),
                entity.getLastCollectStatus(),
                entity.getLastCollectedAt(),
                entity.getNextCollectAt()
        );
    }
}