package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.AccountCredentialRepository;
import com.monitor.platform.collector.repository.entity.AccountCredentialEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号凭证业务服务。
 */
@Service
public class AccountCredentialService {

    private static final Logger log = LoggerFactory.getLogger(AccountCredentialService.class);

    public static final String PASSWORD = "PASSWORD";

    private final AccountCredentialRepository credentialRepository;
    private final CredentialCipher credentialCipher;

    public AccountCredentialService(AccountCredentialRepository credentialRepository,
                                    CredentialCipher credentialCipher) {
        this.credentialRepository = credentialRepository;
        this.credentialCipher = credentialCipher;
    }

    public AccountCredentialEntity savePassword(Integer accountId, String password) {
        log.info("保存账号密码凭证: accountId={}", accountId);
        CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(password);

        AccountCredentialEntity entity = new AccountCredentialEntity();
        entity.setAccountId(accountId);
        entity.setCredentialType(PASSWORD);
        entity.setEncryptionAlgorithm(encrypted.algorithm());
        entity.setEncryptedPayload(encrypted.encryptedPayload());
        entity.setInitializationVector(encrypted.initializationVector());
        entity.setKeyVersion(encrypted.keyVersion());
        entity.setIsActive(true);
        entity.setMetadata("{}");
        return credentialRepository.replaceActive(entity);
    }

    public void deactivateAll(Integer accountId) {
        log.info("停用账号全部凭证: accountId={}", accountId);
        credentialRepository.deactivateAllActive(accountId);
    }

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
}