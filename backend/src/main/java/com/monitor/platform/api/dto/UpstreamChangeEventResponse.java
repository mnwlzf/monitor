package com.monitor.platform.api.dto;

import java.math.BigDecimal;
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
        boolean inUse
) {
}