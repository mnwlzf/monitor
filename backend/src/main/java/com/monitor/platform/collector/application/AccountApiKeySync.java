package com.monitor.platform.collector.application;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.AccountApiKeySnapshotRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountApiKeySnapshotEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.security.CredentialCipher;
import com.monitor.platform.common.util.Sha256Util;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * API Key 增量同步的公共部分。
 *
 * <p>New API 与 Sub2API 的密钥采集差异在「怎么取数、字段怎么映射」，但增量对比、
 * 轮换判定、下线处理、快照写入完全一致，统一放这里。</p>
 */
@Component
public class AccountApiKeySync {

    /** 密钥归一化状态：启用（正在使用）。 */
    private static final String KEY_STATUS_ACTIVE = "ACTIVE";

    private final AccountApiKeyRepository apiKeyRepository;
    private final AccountApiKeySnapshotRepository apiKeySnapshotRepository;
    private final UpstreamChangeRecorder changeRecorder;
    private final CredentialCipher credentialCipher;

    public AccountApiKeySync(AccountApiKeyRepository apiKeyRepository,
                             AccountApiKeySnapshotRepository apiKeySnapshotRepository,
                             UpstreamChangeRecorder changeRecorder,
                             CredentialCipher credentialCipher) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeySnapshotRepository = apiKeySnapshotRepository;
        this.changeRecorder = changeRecorder;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 加密并写入完整密钥；哈希变化时判定为轮换。
     *
     * @return 密钥是否发生轮换
     */
    public boolean applyFullKey(AccountApiKeyEntity entity, boolean isNew, String fullKey) {
        if (fullKey == null || fullKey.isBlank()) {
            return false;
        }
        String hash = Sha256Util.hex(fullKey);
        boolean rotated = !isNew && entity.getKeyHash() != null && !entity.getKeyHash().equals(hash);
        if (isNew || rotated || entity.getKeyHash() == null) {
            CredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(fullKey);
            entity.setKeyEncryptionAlgorithm(encrypted.algorithm());
            entity.setKeyEncryptedPayload(encrypted.encryptedPayload());
            entity.setKeyInitializationVector(encrypted.initializationVector());
            entity.setKeyKeyVersion(encrypted.keyVersion());
            entity.setKeyHash(hash);
        }
        return rotated;
    }

    /** 保存（新增或更新）密钥实体。 */
    public void save(AccountApiKeyEntity entity) {
        apiKeyRepository.save(entity);
    }

    /**
     * 查询账号当前有效密钥，并以上游密钥 ID 建立索引，便于本轮采集做增量对比。
     */
    public Map<String, AccountApiKeyEntity> activeApiKeyMap(Integer accountId) {
        Map<String, AccountApiKeyEntity> result = new HashMap<>();
        for (AccountApiKeyEntity key : apiKeyRepository.findByAccount(accountId, true)) {
            result.put(key.getExternalKeyId(), key);
        }
        return result;
    }

    /**
     * 创建新发现的密钥实体，并初始化首次发现和最后可见时间。
     */
    public AccountApiKeyEntity newApiKey(AccountEntity account, String platformType,
                                         String externalKeyId, OffsetDateTime now) {
        AccountApiKeyEntity entity = new AccountApiKeyEntity();
        entity.setAccountId(account.getId());
        entity.setPlatformType(platformType);
        entity.setExternalKeyId(externalKeyId);
        entity.setIsActive(true);
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        entity.setLastChangedAt(now);
        return entity;
    }

    /**
     * 对比密钥名称、状态和所属分组，逐字段写入变更事件；额度类指标不参与比较。
     *
     * @return 是否至少发生一项变更
     */
    public boolean recordApiKeyChanges(AccountEntity account, String platformType, Long collectionRunId,
                                       AccountApiKeyEntity entity, String keyName, String status,
                                       String groupName) {
        // 变更前后任一时刻处于启用状态，都视为「正在使用的密钥」发生的变更。
        boolean inUse = isKeyInUse(entity.getStatus()) || isKeyInUse(status);
        boolean changed = false;
        if (!Objects.equals(entity.getKeyName(), keyName)) {
            // 名称本身就是变化项，用明确标注的上游 ID 定位密钥，避免旧名被误读成新名。
            changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.API_KEY_UPDATED, "name", entity.getKeyName(), keyName,
                    "密钥名称变化（密钥 ID " + entity.getExternalKeyId() + "）", inUse);
            changed = true;
        }
        if (!Objects.equals(entity.getStatus(), status)) {
            changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.API_KEY_UPDATED, "status", entity.getStatus(), status,
                    "密钥状态变化: " + displayKeyName(entity), inUse);
            changed = true;
        }
        if (!Objects.equals(entity.getGroupName(), groupName)) {
            changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, entity,
                    UpstreamChangeTypes.API_KEY_UPDATED, "group", entity.getGroupName(), groupName,
                    "密钥所属分组变化: " + displayKeyName(entity), inUse);
            changed = true;
        }
        return changed;
    }

    /** 密钥归一化状态是否为启用（正在使用）。 */
    public boolean isKeyInUse(String status) {
        return KEY_STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * 将本轮上游未返回的密钥标记为失效，并记录事件与快照。
     *
     * @return 本轮失效的密钥数量
     */
    public int deactivateMissingApiKeys(AccountEntity account, String platformType, Long collectionRunId,
                                        Map<String, AccountApiKeyEntity> existing,
                                        Set<String> activeExternalIds, OffsetDateTime now) {
        int removed = 0;
        for (AccountApiKeyEntity key : existing.values()) {
            if (activeExternalIds.contains(key.getExternalKeyId())) {
                continue;
            }
            key.setIsActive(false);
            key.setLastChangedAt(now);
            apiKeyRepository.save(key);
            changeRecorder.recordApiKeyChange(account, platformType, collectionRunId, key,
                    UpstreamChangeTypes.API_KEY_REMOVED, "is_active", true, false,
                    "密钥已失效: " + displayKeyName(key), isKeyInUse(key.getStatus()));
            saveApiKeySnapshot(key, collectionRunId, now);
            removed++;
        }
        return removed;
    }

    /**
     * 保存一次密钥状态快照，用于历史查询。
     */
    public void saveApiKeySnapshot(AccountApiKeyEntity entity, Long collectionRunId, OffsetDateTime now) {
        AccountApiKeySnapshotEntity snapshot = new AccountApiKeySnapshotEntity();
        snapshot.setApiKeyId(entity.getId());
        snapshot.setAccountId(entity.getAccountId());
        snapshot.setCollectionRunId(collectionRunId);
        snapshot.setPlatformType(entity.getPlatformType());
        snapshot.setExternalKeyId(entity.getExternalKeyId());
        snapshot.setKeyName(entity.getKeyName());
        snapshot.setStatus(entity.getStatus());
        snapshot.setGroupName(entity.getGroupName());
        snapshot.setUnlimitedQuota(entity.getUnlimitedQuota());
        snapshot.setRemainQuota(entity.getRemainQuota());
        snapshot.setUsedQuota(entity.getUsedQuota());
        snapshot.setQuotaUnit(entity.getQuotaUnit());
        snapshot.setMetrics(entity.getMetrics());
        snapshot.setRawData(entity.getRawData());
        snapshot.setCollectedAt(now);
        apiKeySnapshotRepository.save(snapshot);
    }

    /**
     * 变更说明里的密钥标识：优先名称、其次脱敏值，并统一附上明确标注的上游 ID。
     *
     * <p>只写裸 ID 时无法判断这串数字代表什么，这里始终带上「密钥 ID」前缀，
     * 保证变更记录自解释、无歧义。</p>
     */
    public String displayKeyName(AccountApiKeyEntity entity) {
        String identity = "密钥 ID " + entity.getExternalKeyId();
        if (StrUtil.isNotEmpty(entity.getKeyName())) {
            return entity.getKeyName() + "（" + identity + "）";
        }
        if (StrUtil.isNotEmpty(entity.getKeyMasked())) {
            return entity.getKeyMasked() + "（" + identity + "）";
        }
        return identity;
    }
}
