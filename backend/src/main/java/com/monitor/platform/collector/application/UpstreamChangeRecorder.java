package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上游变更事件记录。
 *
 * <p>把「渠道 / 密钥发生了变化」落成 {@link UpstreamChangeEventEntity}，并顺带判定事件的
 * 级别（{@link #groupChangeSeverity}）与「是否发生在正在使用的对象上」（{@link #groupInUse}）——
 * 这两项决定了后续邮件是实时告警还是每日汇总里的一条。</p>
 *
 * <p>从 {@code CollectionService} 抽出：采集流程负责「发现变化」，本类只负责「把变化写成事件」，
 * 两边职责分开后，改告警口径不必再动采集主流程。</p>
 */
@Component
public class UpstreamChangeRecorder {

    private static final Logger log = LoggerFactory.getLogger(UpstreamChangeRecorder.class);

    private static final String ENTITY_TYPE_GROUP = "GROUP";
    private static final String ENTITY_TYPE_API_KEY = "API_KEY";

    /** 渠道状态：启用（渠道 in_use 的判定依据）。newapi 给的是 {@code ACTIVE}，sub2api 是 {@code active}。 */
    private static final String GROUP_STATUS_ACTIVE = "active";

    private final UpstreamChangeEventRepository changeEventRepository;
    private final CollectionJson json;

    public UpstreamChangeRecorder(UpstreamChangeEventRepository changeEventRepository, CollectionJson json) {
        this.changeEventRepository = changeEventRepository;
        this.json = json;
    }

    /**
     * 记录一条渠道变更事件。
     */
    public void recordGroupChange(AccountEntity account, String platformType, Long collectionRunId,
                                  UpstreamGroupEntity entity, String changeType,
                                  String fieldName, Object oldValue, Object newValue,
                                  String message) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setAccountId(account.getId());
        event.setPlatformType(platformType);
        event.setCollectionRunId(collectionRunId);
        event.setEntityType(ENTITY_TYPE_GROUP);
        event.setEntityId(entity.getId());
        event.setEntityKey(entity.getExternalGroupId());
        event.setChangeType(changeType);
        event.setFieldName(fieldName);
        event.setOldValue(json.toJson(oldValue));
        event.setNewValue(json.toJson(newValue));
        event.setSeverity(groupChangeSeverity(changeType, oldValue, newValue));
        event.setInUse(groupInUse(entity, fieldName, oldValue, newValue));
        event.setMessage(message);
        event.setDetectedAt(OffsetDateTime.now());
        event.setMetadata("{}");
        log.info("记录渠道变更事件: accountId={}, runId={}, platformType={}, changeType={}, field={}, key={}, oldValue={}, newValue={}",
                account.getId(), collectionRunId, platformType, changeType, fieldName,
                entity.getExternalGroupId(), event.getOldValue(), event.getNewValue());
        changeEventRepository.save(event);
    }

    /**
     * 写入 API Key 变更事件。
     */
    public void recordApiKeyChange(AccountEntity account, String platformType, Long collectionRunId,
                                   AccountApiKeyEntity entity, String changeType,
                                   String fieldName, Object oldValue, Object newValue,
                                   String message, boolean inUse) {
        UpstreamChangeEventEntity event = new UpstreamChangeEventEntity();
        event.setAccountId(account.getId());
        event.setPlatformType(platformType);
        event.setCollectionRunId(collectionRunId);
        event.setEntityType(ENTITY_TYPE_API_KEY);
        event.setEntityId(entity.getId());
        event.setEntityKey(entity.getExternalKeyId());
        event.setChangeType(changeType);
        event.setFieldName(fieldName);
        // 新增/轮换密钥的前后值可能是整块上游响应（含明文 key），落库前必须脱敏
        event.setOldValue(json.toRedactedJson(oldValue));
        event.setNewValue(json.toRedactedJson(newValue));
        event.setSeverity("INFO");
        event.setMessage(message);
        event.setInUse(inUse);
        event.setDetectedAt(OffsetDateTime.now());
        event.setMetadata("{}");
        log.info("记录密钥变更事件: accountId={}, runId={}, platformType={}, changeType={}, field={}, keyId={}",
                account.getId(), collectionRunId, platformType, changeType, fieldName, entity.getExternalKeyId());
        changeEventRepository.save(event);
    }

    /**
     * 渠道变更的级别。
     *
     * <p>以前这里写死 {@code "INFO"}，结果「渠道涨价」和「新增一个渠道」长得一模一样，
     * 全淹没在噪声里 —— 而涨价是直接关系到成本的，必须浮出来。</p>
     *
     * <ul>
     *     <li>倍率（含基础倍率）<strong>上调</strong> → {@code WARNING}（成本上升）；下调 → {@code INFO}；</li>
     *     <li>渠道被移除 → {@code WARNING}；</li>
     *     <li>其余（新增、改名、改描述等）→ {@code INFO}。</li>
     * </ul>
     */
    static String groupChangeSeverity(String changeType, Object oldValue, Object newValue) {
        if (UpstreamChangeTypes.RATE_CHANGED.equals(changeType)
                || UpstreamChangeTypes.BASE_RATE_CHANGED.equals(changeType)) {
            BigDecimal before = toDecimal(oldValue);
            BigDecimal after = toDecimal(newValue);
            if (before != null && after != null && after.compareTo(before) > 0) {
                return "WARNING";
            }
            return "INFO";
        }
        if (UpstreamChangeTypes.GROUP_REMOVED.equals(changeType)) {
            return "WARNING";
        }
        return "INFO";
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 这条渠道变更是否发生在「使用中」的渠道上。
     *
     * <p>渠道的口径是<strong>上游把它标成启用</strong>（状态 {@code active} / {@code ACTIVE}），
     * 也就是「这个渠道上游还在售」。注意这和密钥不同：密钥的「使用中」看的是我自己的号池账号
     * 有没有绑定它（见 {@code V30__bind_in_use_key_changes.sql}），上游是否启用只是「活跃」。</p>
     *
     * <p>为什么必须补上：渠道事件以前根本没设过 {@code in_use}，仓库默认填 false ——
     * 结果「正在使用的渠道涨价」和「一个早就下架的渠道改名」在数据里完全一样，
     * 邮件只能全发，全是噪声。</p>
     */
    static boolean groupInUse(UpstreamGroupEntity entity, String fieldName,
                              Object oldValue, Object newValue) {
        if (entity == null) {
            return false;
        }
        if ("is_active".equals(fieldName)) {
            // 渠道被移除：上游已经没有它了，不算「正在使用」
            return false;
        }
        // 其余变更：渠道仍然在上游（is_active）且状态为启用，才算使用中
        return Boolean.TRUE.equals(entity.getIsActive()) && isGroupActive(entity.getStatus());
    }

    private static boolean isGroupActive(Object status) {
        return status != null && GROUP_STATUS_ACTIVE.equalsIgnoreCase(String.valueOf(status).trim());
    }
}
