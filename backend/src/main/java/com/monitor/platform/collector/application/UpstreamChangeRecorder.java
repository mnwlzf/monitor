package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.UpstreamChangeEventRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import com.monitor.platform.collector.repository.entity.UpstreamGroupEntity;
import com.monitor.platform.common.util.ChannelNameMatcher;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 上游变更事件记录。
 *
 * <p>把「渠道 / 密钥发生了变化」落成 {@link UpstreamChangeEventEntity}，并顺带判定事件的
 * 级别（{@link #groupChangeSeverity}）与「是否发生在我正在使用的对象上」（{@link #groupInUse}）——
 * 这两项决定了后续邮件是实时告警还是每日汇总里的一条。</p>
 *
 * <p><strong>「使用中」= 我自己的平台在用它</strong>，两条链路都是这个口径：
 * 密钥看号池账号有没有绑定它（{@code V30}），渠道看这个渠道里有没有我绑定的 Key（{@code V31}）。
 * 上游自己把渠道 / Key 标成启用只是「活跃」，不代表我在用。</p>
 *
 * <p>从 {@code CollectionService} 抽出：采集流程负责「发现变化」，本类只负责「把变化写成事件」，
 * 两边职责分开后，改告警口径不必再动采集主流程。</p>
 */
@Component
public class UpstreamChangeRecorder {

    private static final Logger log = LoggerFactory.getLogger(UpstreamChangeRecorder.class);

    private static final String ENTITY_TYPE_GROUP = "GROUP";
    private static final String ENTITY_TYPE_API_KEY = "API_KEY";

    private final UpstreamChangeEventRepository changeEventRepository;
    private final CollectionJson json;
    private final PoolAccountRepository poolAccountRepository;

    public UpstreamChangeRecorder(UpstreamChangeEventRepository changeEventRepository,
                                  CollectionJson json,
                                  PoolAccountRepository poolAccountRepository) {
        this.changeEventRepository = changeEventRepository;
        this.json = json;
        this.poolAccountRepository = poolAccountRepository;
    }

    /**
     * 该上游账号下「我正在使用」的渠道名：渠道里有本平台号池账号绑定的上游 Key。
     *
     * <p>一次采集查一次，之后逐条判定只看内存。</p>
     */
    public Set<String> inUseChannelNames(Integer accountId) {
        return poolAccountRepository.findInUseChannelNames(accountId);
    }

    /**
     * 记录一条渠道变更事件。
     *
     * @param inUseChannelNames 该账号下「我正在使用」的渠道名（见 {@link #inUseChannelNames}）
     */
    public void recordGroupChange(AccountEntity account, String platformType, Long collectionRunId,
                                  UpstreamGroupEntity entity, String changeType,
                                  String fieldName, Object oldValue, Object newValue,
                                  String message, Set<String> inUseChannelNames) {
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
        event.setInUse(groupInUse(entity.getGroupName(), fieldName, inUseChannelNames));
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
     * 这条渠道变更是否发生在「我正在使用」的渠道上。
     *
     * <p>口径：<strong>这个渠道里有本平台号池账号绑定的上游 Key</strong>，也就是
     * 「我自己的平台在用它」。上游把渠道标成启用只是在售（页面上标「活跃」），不代表我在用；
     * 上游改过分组名（Key 上还是旧名字）由 {@link ChannelNameMatcher} 兜住。</p>
     *
     * <p>为什么必须补上：渠道事件以前根本没设过 {@code in_use}，仓库默认填 false ——
     * 结果「我正在用的渠道涨价」和「一个早就下架的渠道改名」在数据里完全一样，
     * 邮件只能全发，全是噪声。</p>
     */
    static boolean groupInUse(String groupName, String fieldName, Set<String> inUseChannelNames) {
        if ("is_active".equals(fieldName)) {
            // 渠道被移除：上游已经没有它了，不算「我正在使用」
            return false;
        }
        return ChannelNameMatcher.matchesAny(inUseChannelNames, groupName);
    }
}
