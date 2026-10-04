package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * New API 用户密钥列表响应。
 *
 * <p>接口路径：{@code GET /api/token/?p={page}&size={size}}。列表中的 {@code key}
 * 为脱敏值，完整明文需要通过 {@code POST /api/token/{id}/key} 单独获取。</p>
 *
 * @param data    分页数据
 * @param message 业务提示信息
 * @param success 是否请求成功
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiTokensResponse(
        Data data,
        String message,
        boolean success
) {

    /**
     * 密钥分页结果。
     *
     * @param page     当前页码
     * @param pageSize 每页记录数
     * @param total    总记录数
     * @param items    当前页密钥列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Integer page,
            @JsonProperty("page_size") Integer pageSize,
            Integer total,
            List<Item> items
    ) {
    }

    /**
     * 单个密钥。额度字段为 New API 内部额度单位，换算为 USD 时除以 500000。
     *
     * @param id                 密钥 ID，轮换检测与完整密钥查询的唯一标识
     * @param userId             所属用户 ID
     * @param key                脱敏后的密钥
     * @param status             上游状态码：1 启用、2 禁用、3 已过期、4 已耗尽
     * @param name               密钥名称
     * @param createdTime        创建时间，Unix 秒
     * @param accessedTime       最近使用时间，Unix 秒
     * @param expiredTime        过期时间，Unix 秒，-1 表示永不过期
     * @param remainQuota        剩余额度，内部单位
     * @param unlimitedQuota     是否不限额度
     * @param modelLimitsEnabled 是否启用模型限制
     * @param modelLimits        模型限制内容
     * @param allowIps           IP 白名单
     * @param usedQuota          累计已用额度，内部单位
     * @param group              所属分组名称
     * @param crossGroupRetry    是否跨分组重试
     * @param groupRouteConfig   分组路由配置
     * @param groupRouteSticky   是否粘性分组路由
     * @param deletedAt          软删除标记
     * @param autoGroups         自动分组配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Long id,
            @JsonProperty("user_id") Long userId,
            String key,
            Integer status,
            String name,
            @JsonProperty("created_time") Long createdTime,
            @JsonProperty("accessed_time") Long accessedTime,
            @JsonProperty("expired_time") Long expiredTime,
            @JsonProperty("remain_quota") Long remainQuota,
            @JsonProperty("unlimited_quota") Boolean unlimitedQuota,
            @JsonProperty("model_limits_enabled") Boolean modelLimitsEnabled,
            @JsonProperty("model_limits") String modelLimits,
            @JsonProperty("allow_ips") String allowIps,
            @JsonProperty("used_quota") Long usedQuota,
            String group,
            @JsonProperty("cross_group_retry") Boolean crossGroupRetry,
            @JsonProperty("group_route_config") String groupRouteConfig,
            @JsonProperty("group_route_sticky") Boolean groupRouteSticky,
            @JsonProperty("DeletedAt") Object deletedAt,
            @JsonProperty("auto_groups") Object autoGroups
    ) {
    }
}
