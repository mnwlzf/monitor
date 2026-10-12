package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.AccountApiKeyResponse;
import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.CreateAccountRequest;
import com.monitor.platform.api.dto.CreatePlatformRequest;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpdateAccountRequest;
import com.monitor.platform.api.dto.UpdatePlatformRequest;
import com.monitor.platform.api.dto.UpstreamGroupResponse;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.UpstreamGroupRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 上游平台与账号管理：平台/账号增删改查、凭证、渠道与密钥的读取。
 *
 * <p>用量看板、指标时序、变更事件等只读统计查询见 {@link UpstreamUsageService}。</p>
 */
@Service
public class UpstreamAdminService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamAdminService.class);

    /** 支持手动 Token 登录的平台类型。 */
    private static final String SUPPORTED_TOKEN_PLATFORM = "sub2api";

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final UpstreamGroupRepository groupRepository;
    private final AccountApiKeyRepository apiKeyRepository;
    private final AccountCredentialService credentialService;
    private final CredentialCipher credentialCipher;
    private final JsonPayloads json;
    private final UpstreamAccountAccess accounts;

    public UpstreamAdminService(PlatformRepository platformRepository,
                                AccountRepository accountRepository,
                                UpstreamGroupRepository groupRepository,
                                AccountApiKeyRepository apiKeyRepository,
                                AccountCredentialService credentialService,
                                CredentialCipher credentialCipher,
                                JsonPayloads json,
                                UpstreamAccountAccess accounts) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.groupRepository = groupRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.credentialService = credentialService;
        this.credentialCipher = credentialCipher;
        this.json = json;
        this.accounts = accounts;
    }

    /**
     * 创建平台实例。平台名称全局唯一，创建后默认启用。
     *
     * @param request 平台创建参数
     * @return 新建平台
     */
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
        applyAdminKey(entity, request.adminKey(), false);
        entity.setPoolMonitoringEnabled(Boolean.TRUE.equals(request.poolMonitoringEnabled()));
        platformRepository.save(entity);

        log.info("创建上游平台成功: platformId={}, name={}, type={}, baseUrl={}",
                entity.getId(), entity.getPlatformName(), entity.getPlatformType(), entity.getUrl());
        return toPlatformResponse(entity);
    }

    /**
     * 查询所有未删除平台（含已停用），供管理页面展示与启用/停用切换。
     *
     * @return 平台列表
     */
    public List<PlatformResponse> listPlatforms() {
        return platformRepository.findAll().stream().map(this::toPlatformResponse).toList();
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
        applyAdminKey(entity, request.adminKey(), Boolean.TRUE.equals(request.clearAdminKey()));
        if (request.poolMonitoringEnabled() != null) {
            entity.setPoolMonitoringEnabled(request.poolMonitoringEnabled());
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

    /**
     * 在指定平台下创建账号，并保存加密后的登录凭证。
     *
     * @param platformId 平台 ID
     * @param request    账号和密码信息
     * @return 新建账号
     */
    @Transactional
    public AccountResponse createAccount(Integer platformId, CreateAccountRequest request) {
        PlatformEntity platform = platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));

        accountRepository.findByPlatformIdAndEmail(platformId, request.loginName()).ifPresent(existing -> {
            throw BusinessException.of("该平台下账号已存在: " + request.loginName());
        });

        String authType = normalizeAuthType(request.authType());
        boolean tokenAuth = AccountCredentialService.TOKEN.equals(authType);
        if (tokenAuth) {
            if (isBlank(request.refreshToken()) && isBlank(request.accessToken())) {
                throw BusinessException.of("使用 Token 登录时，请至少填写 refresh_token 或 access_token");
            }
        } else if (isBlank(request.password())) {
            throw BusinessException.of("密码登录必须填写登录密码");
        }
        if (tokenAuth && !SUPPORTED_TOKEN_PLATFORM.equalsIgnoreCase(platform.getPlatformType())) {
            throw BusinessException.of("手动 Token 登录目前仅支持 Sub2API 平台");
        }

        AccountEntity entity = new AccountEntity();
        entity.setPlatformId(platformId);
        entity.setPlatform(platform.getPlatformType());
        entity.setUrl(platform.getUrl());
        entity.setEmail(request.loginName());
        entity.setUsername(request.loginName());
        entity.setDisplayName(request.displayName());
        entity.setStatus(true);
        entity.setAuthType(authType);
        entity.setCredentialStatus("UNKNOWN");
        entity.setConsecutiveFailures(0);
        entity.setSettings("{}");
        accountRepository.save(entity);

        if (tokenAuth) {
            saveAccountTokens(entity.getId(), request.refreshToken(), request.accessToken());
        } else {
            credentialService.savePassword(entity.getId(), request.password());
        }

        log.info("创建采集账号成功: accountId={}, platformId={}, loginName={}, authType={}",
                entity.getId(), platformId, request.loginName(), entity.getAuthType());
        return accounts.toResponse(entity);
    }

    /**
     * 更新指定平台下的账号信息。密码为空时不覆盖原凭证。
     *
     * @param platformId 平台 ID
     * @param accountId  账号 ID
     * @param request    待更新字段
     * @return 更新后的账号
     */
    @Transactional
    public AccountResponse updateAccount(Integer platformId, Integer accountId,
                                         UpdateAccountRequest request) {
        AccountEntity entity = accounts.require(platformId, accountId);

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
            entity.setAuthType(normalizeAuthType(request.authType()));
        }
        if (request.status() != null) {
            entity.setStatus(request.status());
        }

        // 填了 token 就按 Token 登录；填了密码就切回密码登录
        if (notBlank(request.refreshToken()) || notBlank(request.accessToken())) {
            saveAccountTokens(accountId, request.refreshToken(), request.accessToken());
            entity.setAuthType(AccountCredentialService.TOKEN);
        }
        if (notBlank(request.password())) {
            credentialService.savePassword(accountId, request.password());
            entity.setAuthType(AccountCredentialService.PASSWORD);
        }
        if (AccountCredentialService.TOKEN.equalsIgnoreCase(entity.getAuthType())) {
            PlatformEntity accountPlatform = platformRepository.findById(platformId).orElse(null);
            if (accountPlatform != null
                    && !SUPPORTED_TOKEN_PLATFORM.equalsIgnoreCase(accountPlatform.getPlatformType())) {
                throw BusinessException.of("手动 Token 登录目前仅支持 Sub2API 平台");
            }
            if (!credentialService.hasCredential(accountId, AccountCredentialService.REFRESH_TOKEN)
                    && !credentialService.hasCredential(accountId, AccountCredentialService.ACCESS_TOKEN)) {
                throw BusinessException.of("使用 Token 登录时，请至少填写 refresh_token 或 access_token");
            }
        } else if (!credentialService.hasCredential(accountId, AccountCredentialService.PASSWORD)) {
            throw BusinessException.of("密码登录必须填写登录密码");
        }

        accountRepository.save(entity);

        log.info("更新采集账号成功: accountId={}, platformId={}, loginName={}, authType={}",
                accountId, platformId, entity.getUsername(), entity.getAuthType());
        return accounts.toResponse(entity);
    }

    /**
     * 软删除账号，并停用该账号的全部凭证。
     *
     * @param platformId 平台 ID
     * @param accountId  账号 ID
     */
    @Transactional
    public void deleteAccount(Integer platformId, Integer accountId) {
        AccountEntity entity = accounts.require(platformId, accountId);
        accountRepository.softDelete(accountId);
        credentialService.deactivateAll(accountId);
        log.info("删除采集账号成功: accountId={}, platformId={}, loginName={}",
                accountId, platformId, entity.getUsername());
    }
    /**
     * 查询指定平台下的账号列表。
     *
     * @param platformId 平台 ID
     * @return 账号列表
     */
    public List<AccountResponse> listAccounts(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        return accountRepository.findByPlatformId(platformId).stream().map(accounts::toResponse).toList();
    }

    /** 归一化认证类型：只有显式 TOKEN 才按 Token 处理，其余按密码登录。 */
    private String normalizeAuthType(String authType) {
        if (authType != null && AccountCredentialService.TOKEN.equalsIgnoreCase(authType.trim())) {
            return AccountCredentialService.TOKEN;
        }
        return AccountCredentialService.PASSWORD;
    }

    /** 保存页面手动填写的 token；空值表示不修改。 */
    private void saveAccountTokens(Integer accountId, String refreshToken, String accessToken) {
        if (notBlank(refreshToken)) {
            credentialService.saveToken(accountId, AccountCredentialService.REFRESH_TOKEN, refreshToken.trim());
        }
        if (notBlank(accessToken)) {
            credentialService.saveToken(accountId, AccountCredentialService.ACCESS_TOKEN, accessToken.trim());
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 将渠道实体转换为接口响应。
     */
    private UpstreamGroupResponse toGroupResponse(UpstreamGroupEntity entity) {
        return new UpstreamGroupResponse(
                entity.getId(), entity.getAccountId(), entity.getExternalGroupId(),
                entity.getGroupName(), entity.getDescription(), entity.getPlatform(),
                entity.getCurrentRatio(), entity.getCurrentBaseRatio(), entity.getStatus(),
                entity.getIsActive(), entity.getFirstSeenAt(), entity.getLastSeenAt(), entity.getLastChangedAt()
        );
    }

    /**
     * 将平台实体转换为接口响应。
     */
    private PlatformResponse toPlatformResponse(PlatformEntity entity) {
        return new PlatformResponse(
                entity.getId(),
                entity.getPlatformName(),
                entity.getUrl(),
                entity.getPlatformType(),
                entity.getStatus(),
                entity.hasAdminKey(),
                Boolean.TRUE.equals(entity.getPoolMonitoringEnabled())
        );
    }

    /**
     * 写入或清除平台的管理员密钥密文。
     *
     * <p>{@code clear} 为真时清空全部密钥字段；否则仅在 {@code adminKey} 非空时加密覆盖，
     * 传入空值表示保留原密钥（编辑平台时不回显明文，避免误清空）。</p>
     */
    private void applyAdminKey(PlatformEntity entity, String adminKey, boolean clear) {
        if (clear) {
            entity.setAdminKeyEncryptionAlgorithm(null);
            entity.setAdminKeyEncryptedPayload(null);
            entity.setAdminKeyInitializationVector(null);
            entity.setAdminKeyKeyVersion(null);
            return;
        }
        if (adminKey == null || adminKey.isBlank()) {
            return;
        }
        CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(adminKey.trim());
        entity.setAdminKeyEncryptionAlgorithm(encrypted.algorithm());
        entity.setAdminKeyEncryptedPayload(encrypted.encryptedPayload());
        entity.setAdminKeyInitializationVector(encrypted.initializationVector());
        entity.setAdminKeyKeyVersion(encrypted.keyVersion());
    }

    /**
     * 查询平台下所有账号的渠道/分组。
     *
     * @param platformId 平台 ID
     * @return 渠道列表
     */
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

    /**
     * 查询平台下所有账号的 API Key。
     *
     * <p>只返回脱敏后的密钥，完整明文需通过显式解密接口获取。</p>
     *
     * @param platformId 平台 ID
     * @return 密钥列表
     */
    public List<AccountApiKeyResponse> listApiKeys(Integer platformId) {
        platformRepository.findById(platformId)
                .orElseThrow(() -> BusinessException.of("平台不存在: " + platformId));
        List<AccountApiKeyResponse> result = new ArrayList<>();
        for (AccountEntity account : accountRepository.findByPlatformId(platformId)) {
            for (AccountApiKeyEntity apiKey : apiKeyRepository.findByAccount(account.getId(), false)) {
                result.add(toApiKeyResponse(account, apiKey));
            }
        }
        return result;
    }

    /**
     * 解密并返回指定密钥的完整明文。
     *
     * <p>属于敏感操作，仅管理员可调用。</p>
     *
     * @param platformId 平台 ID
     * @param accountId  账号 ID
     * @param keyId      密钥主键
     * @return 完整明文密钥
     */
    public String revealApiKeySecret(Integer platformId, Integer accountId, Long keyId) {
        AccountEntity account = accounts.require(platformId, accountId);
        AccountApiKeyEntity apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> BusinessException.of("密钥不存在: " + keyId));
        if (!account.getId().equals(apiKey.getAccountId())) {
            throw BusinessException.of("密钥不属于指定账号: " + keyId);
        }
        if (apiKey.getKeyEncryptedPayload() == null || apiKey.getKeyEncryptedPayload().isBlank()) {
            throw BusinessException.of("该密钥未保存密文，无法获取明文");
        }
        return credentialCipher.decrypt(
                apiKey.getKeyEncryptedPayload(),
                apiKey.getKeyInitializationVector(),
                apiKey.getKeyEncryptionAlgorithm(),
                apiKey.getKeyKeyVersion()
        );
    }

    /**
     * 解密并返回账号的登录密码明文。
     *
     * <p>属于敏感操作，仅管理员可调用。密码是采集登录上游所必需的凭证，
     * 这里复用与采集相同的解密路径，并记录操作人以便审计。</p>
     *
     * @param platformId 平台 ID
     * @param accountId  账号 ID
     * @param operator   操作人，用于审计日志
     * @return 明文密码
     */
    public String revealAccountPassword(Integer platformId, Integer accountId, String operator) {
        AccountEntity account = accounts.require(platformId, accountId);

        String password;
        try {
            password = credentialService.resolvePassword(account.getId());
        } catch (IllegalStateException ex) {
            throw BusinessException.of("该账号没有可用的密码凭证，无法查看明文");
        }

        log.warn("查看账号密码明文: operator={}, platformId={}, accountId={}, loginName={}",
                operator, platformId, accountId, account.getUsername());
        return password;
    }

    /**
     * 将密钥实体转换为接口响应。
     */
    private AccountApiKeyResponse toApiKeyResponse(AccountEntity account, AccountApiKeyEntity entity) {
        String accountName = account.getDisplayName();
        if (accountName == null || accountName.isBlank()) {
            accountName = account.getUsername() == null ? account.getEmail() : account.getUsername();
        }
        return new AccountApiKeyResponse(
                entity.getId(), entity.getAccountId(), account.getPlatformId(), accountName,
                entity.getPlatformType(), entity.getExternalKeyId(), entity.getKeyName(),
                entity.getKeyMasked(), entity.getStatus(), entity.getUpstreamStatus(),
                entity.getGroupName(), entity.getGroupPlatform(), entity.getUnlimitedQuota(),
                entity.getRemainQuota(), entity.getUsedQuota(), entity.getQuotaUnit(),
                entity.getModelLimitsEnabled(), entity.getModelLimits(), entity.getAllowIps(),
                entity.getExpiresAt(), entity.getUpstreamCreatedAt(), entity.getLastUsedAt(),
                entity.getIsActive(), entity.getFirstSeenAt(), entity.getLastSeenAt(),
                entity.getLastChangedAt(), json.read(entity.getMetrics())
        );
    }
}
