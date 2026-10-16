package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.time.OffsetDateTime;

/**
 * 上游变更事件响应。
 *
 * @param id           事件主键
 * @param accountId    账号主键
 * @param platformType 平台类型
 * @param entityType   变更实体类型
 * @param entityKey    上游实体标识
 * @param groupName    渠道名（只有 GROUP 事件有；页面只显示名字，光给 ID 没法对号入座）
 * @param accountName  变更所属账号名（accounts 的展示名 / 用户名），即「我自己的哪个 sub2api 账号」
 * @param keyName      上游密钥名（只有 API_KEY 事件有，按 account_id + external_key_id 匹配）
 * @param upstreamKeyNames 受影响的「上游账号 Key」名字，多个用「、」连接（渠道事件按同分组匹配）
 * @param localKeyNames    对应的「本平台 Key」名字（号池账号名），多个用「、」连接
 * @param groupRatio   所在分组的当前倍率（渠道事件）
 * @param impacts      渠道倍率变化对本平台的影响（一行 = 本平台账号 × 分组）；其它事件为空
 * @param changeType   变更类型
 * @param fieldName    发生变化的字段
 * @param oldValue     变更前值
 * @param newValue     变更后值
 * @param severity     事件级别
 * @param message      可读说明
 * @param detectedAt   发现时间
 * @param inUse        变更是否涉及正在使用（启用）的密钥
 */
public record UpstreamChangeEventResponse(
        Long id,
        Integer accountId,
        String platformType,
        String entityType,
        String entityKey,
        String groupName,
        String accountName,
        String keyName,
        String upstreamKeyNames,
        String localKeyNames,
        BigDecimal groupRatio,
        String changeType,
        String fieldName,
        String oldValue,
        String newValue,
        String severity,
        String message,
        OffsetDateTime detectedAt,
        boolean inUse,
        List<ChangeImpact> impacts
) {

    /**
     * 渠道倍率变化对「我自己的 Sub2API」的影响。
     *
     * @param accountName       本平台号池账号名（= 我自己平台的 Key 名）
     * @param externalAccountId 本平台号池账号 ID
     * @param upstreamKeyName   上游 Key 名（account_api_keys.key_name）
     * @param groupName         本平台分组名
     * @param groupPlatform     本平台分组所属平台（openai / anthropic …）
     * @param groupRatio        分组倍率
     * @param oldRatio          上游原倍率
     * @param newRatio          上游新倍率
     * @param increasePercent   涨价幅度（%）
     * @param affected          上游新倍率是否已超过该分组倍率（倒挂）
     * @param level             提醒级别：INFO / WARNING / CRITICAL
     */
    public record ChangeImpact(
            String accountName,
            Long externalAccountId,
            String upstreamKeyName,
            String groupName,
            String groupPlatform,
            BigDecimal groupRatio,
            BigDecimal oldRatio,
            BigDecimal newRatio,
            BigDecimal increasePercent,
            boolean affected,
            String level
    ) {
    }
}