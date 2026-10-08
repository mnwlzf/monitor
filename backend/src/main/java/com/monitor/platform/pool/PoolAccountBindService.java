package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.util.Sha256Util;
import com.monitor.platform.pool.client.Sub2AdminAccountCredential;
import com.monitor.platform.pool.client.Sub2AdminClient;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 号池账号与本地上游 Key 的绑定服务。
 *
 * <p>绑定关系用于把「号池账号的调用指标」关联回「本项目的上游账号/Key」。
 * 自动绑定通过管理员导出接口拿到明文 Key，在内存中计算 SHA-256 后与本地
 * {@code account_api_keys.key_hash} 比对；明文绝不落库、绝不写日志。</p>
 */
@Service
public class PoolAccountBindService {

    private static final Logger log = LoggerFactory.getLogger(PoolAccountBindService.class);

    private final PoolAccountRepository poolAccountRepository;
    private final AccountApiKeyRepository apiKeyRepository;
    private final AccountRepository accountRepository;
    private final Sub2AdminClient adminClient;

    public PoolAccountBindService(PoolAccountRepository poolAccountRepository,
                                  AccountApiKeyRepository apiKeyRepository,
                                  AccountRepository accountRepository,
                                  Sub2AdminClient adminClient) {
        this.poolAccountRepository = poolAccountRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.accountRepository = accountRepository;
        this.adminClient = adminClient;
    }

    /**
     * 自动绑定：按名称与本地密钥哈希匹配号池账号。
     *
     * @return 绑定成功的号池账号数量
     */
    public int autoBind(Integer platformId, String baseUrl, String adminKey) {
        List<Sub2AdminAccountCredential> credentials;
        try {
            credentials = adminClient.fetchAccountCredentials(baseUrl, adminKey);
        } catch (RuntimeException ex) {
            log.warn("拉取号池账号凭证失败，跳过自动绑定: platformId={}, reason={}", platformId, ex.getMessage());
            return 0;
        }
        if (credentials.isEmpty()) {
            log.info("号池账号凭证为空，跳过自动绑定: platformId={}", platformId);
            return 0;
        }

        Map<String, PoolAccountEntity> byName = new HashMap<>();
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            if (account.getName() != null) {
                byName.putIfAbsent(account.getName(), account);
            }
        }

        int bound = 0;
        for (Sub2AdminAccountCredential credential : credentials) {
            PoolAccountEntity account = byName.get(credential.name());
            if (account == null) {
                continue;
            }
            String keyHash = Sha256Util.hex(credential.apiKey());
            Optional<Long> keyId = apiKeyRepository.findActiveKeyIdByHash(platformId, keyHash);
            if (keyId.isEmpty()) {
                continue;
            }
            if (!keyId.get().equals(account.getBoundKeyId())) {
                poolAccountRepository.updateBinding(platformId, account.getExternalAccountId(), keyId.get());
                account.setBoundKeyId(keyId.get());
            }
            bound++;
        }
        log.info("号池账号自动绑定完成: platformId={}, credentialCount={}, boundCount={}",
                platformId, credentials.size(), bound);
        return bound;
    }

    /**
     * 手动绑定号池账号与本地密钥；{@code keyId} 为空表示解除绑定。
     */
    public void bind(Integer platformId, Long externalAccountId, Long keyId) {
        poolAccountRepository.findByPlatformAndExternalId(platformId, externalAccountId)
                .orElseThrow(() -> BusinessException.of("号池账号不存在: " + externalAccountId));

        if (keyId != null) {
            AccountApiKeyEntity key = apiKeyRepository.findById(keyId)
                    .orElseThrow(() -> BusinessException.of("本地密钥不存在: " + keyId));
            AccountEntity account = accountRepository.findById(key.getAccountId())
                    .orElseThrow(() -> BusinessException.of("密钥所属账号不存在: " + key.getAccountId()));
            if (!platformId.equals(account.getPlatformId())) {
                throw BusinessException.of("该密钥不属于当前平台，无法绑定");
            }
        }

        poolAccountRepository.updateBinding(platformId, externalAccountId, keyId);
        log.info("号池账号绑定更新: platformId={}, externalAccountId={}, keyId={}",
                platformId, externalAccountId, keyId);
    }
}