package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sub2API 创建密钥请求。
 *
 * <p>接口：{@code POST /api/v1/keys}（用户 JWT 鉴权）。字段与 Sub2API
 * {@code CreateAPIKeyRequest} 对齐：名称必填；分组、额度、有效期可选。</p>
 *
 * @param name          密钥名称
 * @param groupId       所属分组 ID
 * @param quota         额度限制（USD，0 = 不限）
 * @param expiresInDays 有效期天数（null = 永久）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Sub2CreateKeyRequest(
        String name,
        @JsonProperty("group_id") Long groupId,
        Double quota,
        @JsonProperty("expires_in_days") Integer expiresInDays
) {
}
