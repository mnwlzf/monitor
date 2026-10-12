package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.collector.repository.AccountMetricSnapshotRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.AccountMetricSnapshotEntity;
import com.monitor.platform.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 账号的公共访问规则：归属校验 + 接口响应映射。
 *
 * <p>账号管理、用量看板、指标查询都要「按 (平台, 账号) 取账号」，
 * 共用一份校验，避免跨平台操作的判断口径散落多处。</p>
 */
@Component
public class UpstreamAccountAccess {

    private final AccountRepository accountRepository;
    private final AccountMetricSnapshotRepository metricSnapshotRepository;

    public UpstreamAccountAccess(AccountRepository accountRepository,
                                 AccountMetricSnapshotRepository metricSnapshotRepository) {
        this.accountRepository = accountRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
    }

    /**
     * 校验账号存在且确实属于指定平台，防止跨平台操作。
     */
    public AccountEntity require(Integer platformId, Integer accountId) {
        AccountEntity entity = accountRepository.findById(accountId)
                .orElseThrow(() -> BusinessException.of("账号不存在: " + accountId));
        if (!platformId.equals(entity.getPlatformId())) {
            throw BusinessException.of("账号不属于指定平台: " + accountId);
        }
        return entity;
    }

    /**
     * 账号实体 + 最新指标快照 → 接口响应。
     */
    public AccountResponse toResponse(AccountEntity entity) {
        AccountMetricSnapshotEntity snapshot = metricSnapshotRepository.findLatest(entity.getId()).orElse(null);
        return new AccountResponse(
                entity.getId(),
                entity.getPlatformId(),
                entity.getDisplayName(),
                entity.getUsername(),
                entity.getPlatform(),
                entity.getAuthType(),
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