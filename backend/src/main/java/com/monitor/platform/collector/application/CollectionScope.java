package com.monitor.platform.collector.application;

/**
 * 采集范围。
 *
 * <p>每个范围对应上游一组互不重叠的接口请求，拆成独立定时任务后可以分别控制频率：</p>
 * <ul>
 *   <li>{@link #BALANCE}：New API {@code /api/user/self}（余额与用量看板同源）；
 *       Sub2API {@code /api/v1/auth/me} + {@code /api/v1/usage/dashboard/stats}。</li>
 *   <li>{@link #GROUPS}：分组与渠道倍率。</li>
 *   <li>{@link #API_KEYS}：API Key 列表与用量。</li>
 *   <li>{@link #FULL}：余额 + 分组 + API Key，供手动采集与旧的默认任务使用。</li>
 * </ul>
 */
public enum CollectionScope {

    /** 全量：余额 + 分组 + API Key。 */
    FULL,
    /** 余额：余额/额度与用量看板。 */
    BALANCE,
    /** 分组：分组与渠道倍率。 */
    GROUPS,
    /** API Key：密钥列表与用量。 */
    API_KEYS;

    /** 该范围是否包含余额采集（只有余额任务维护账号健康状态）。 */
    public boolean includesBalance() {
        return this == FULL || this == BALANCE;
    }

    /** 该范围是否包含分组/渠道倍率采集。 */
    public boolean includesGroups() {
        return this == FULL || this == GROUPS;
    }

    /** 该范围是否包含 API Key 采集。 */
    public boolean includesApiKeys() {
        return this == FULL || this == API_KEYS;
    }
}