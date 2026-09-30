package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.CreateAccountRequest;
import com.monitor.platform.api.dto.CreatePlatformRequest;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpdateAccountRequest;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 上游平台和账号管理服务。
 */
@Service
public class UpstreamAdminService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamAdminService.class);

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountCredentialService credentialService;

    public UpstreamAdminService(PlatformRepository platformRepository,
                                AccountRepository accountRepository,
                                AccountCredentialService credentialService) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.credentialService = credentialService;
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
    private PlatformResponse toPlatformResponse(PlatformEntity entity) {
        return new PlatformResponse(
                entity.getId(),
                entity.getPlatformName(),
                entity.getUrl(),
                entity.getPlatformType(),
                entity.getStatus()
        );
    }

    private AccountResponse toAccountResponse(AccountEntity entity) {
        return new AccountResponse(
                entity.getId(),
                entity.getPlatformId(),
                entity.getDisplayName(),
                entity.getUsername(),
                entity.getPlatform(),
                entity.getCredentialStatus(),
                entity.getStatus()
        );
    }
}