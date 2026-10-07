package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.AccountCredentialRepository;
import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 账号凭证业务服务。
 *
 * <p>支持两种认证方式：{@link #PASSWORD}（邮箱+密码登录）与 {@link #TOKEN}
 * （页面手动填写 access_token / refresh_token）。凭证统一使用 AES-GCM 加密落库。</p>
 */
@Service
public class AccountCredentialService {

    private static final Logger log = LoggerFactory.getLogger(AccountCredentialService.class);

    /** 认证类型：密码登录。 */
    public static final String PASSWORD = "PASSWORD";

    /** 认证类型：手动 Token。 */
    public static final String TOKEN = "TOKEN";

    /** 凭证类型：手动填写的访问令牌。 */
    public static final String ACCESS_TOKEN = "ACCESS_TOKEN";

    /** 凭证类型：手动填写的刷新令牌。 */
    public static final String REFRESH_TOKEN = "REFRESH_TOKEN";

    private final AccountCredentialRepository credentialRepository;
    private final CredentialCipher credentialCipher;

    public AccountCredentialService(AccountCredentialRepository credentialRepository,
                                    CredentialCipher credentialCipher) {
        this.credentialRepository = credentialRepository;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 保存账号密码凭证。新凭证会替换同账号同类型的旧凭证。
     *
     * @param accountId 账号 ID
     * @param password  明文密码，仅在内存中短暂存在
     * @return 已落库的凭证实体
     */
    public AccountCredentialEntity savePassword(Integer accountId, String password) {
        return saveCredential(accountId, PASSWORD, password, "密码");
    }

    /**
     * 保存账号 Token 凭证（{@link #ACCESS_TOKEN} / {@link #REFRESH_TOKEN}）。
     *
     * @param accountId      账号 ID
     * @param credentialType 凭证类型
     * @param token          明文 Token，仅在内存中短暂存在
     * @return 已落库的凭证实体
     */
    public AccountCredentialEntity saveToken(Integer accountId, String credentialType, String token) {
        return saveCredential(accountId, credentialType, token, "Token");
    }

    /**
     * 读取并解密指定类型的 Token；未配置时返回空。
     */
    public Optional<String> resolveToken(Integer accountId, String credentialType) {
        return credentialRepository.findActive(accountId, credentialType)
                .map(credential -> credentialCipher.decrypt(
                        credential.getEncryptedPayload(),
                        credential.getInitializationVector(),
                        credential.getEncryptionAlgorithm(),
                        credential.getKeyVersion()));
    }

    /**
     * 是否存在指定类型的有效凭证。
     */
    public boolean hasCredential(Integer accountId, String credentialType) {
        return credentialRepository.findActive(accountId, credentialType).isPresent();
    }

    /**
     * 停用账号的全部有效凭证，通常用于删除账号。
     */
    public void deactivateAll(Integer accountId) {
        log.info("停用账号全部凭证: accountId={}", accountId);
        credentialRepository.deactivateAllActive(accountId);
    }

    /**
     * 读取并解密账号密码；没有有效凭证时直接失败。
     *
     * @param accountId 账号 ID
     * @return 明文密码
     */
    public String resolvePassword(Integer accountId) {
        AccountCredentialEntity credential = credentialRepository.findActive(accountId, PASSWORD)
                .orElseThrow(() -> new IllegalStateException("账号未配置可用密码凭证: " + accountId));
        log.info("读取账号密码凭证: accountId={}, credentialId={}", accountId, credential.getId());

        return credentialCipher.decrypt(
                credential.getEncryptedPayload(),
                credential.getInitializationVector(),
                credential.getEncryptionAlgorithm(),
                credential.getKeyVersion()
        );
    }

    /**
     * 加密并替换指定账号、指定类型的有效凭证。
     */
    private AccountCredentialEntity saveCredential(Integer accountId, String credentialType,
                                                   String value, String label) {
        log.info("保存账号{}凭证: accountId={}, credentialType={}", label, accountId, credentialType);
        CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(value);

        AccountCredentialEntity entity = new AccountCredentialEntity();
        entity.setAccountId(accountId);
        entity.setCredentialType(credentialType);
        entity.setEncryptionAlgorithm(encrypted.algorithm());
        entity.setEncryptedPayload(encrypted.encryptedPayload());
        entity.setInitializationVector(encrypted.initializationVector());
        entity.setKeyVersion(encrypted.keyVersion());
        entity.setIsActive(true);
        entity.setMetadata("{}");
        return credentialRepository.replaceActive(entity);
    }
}