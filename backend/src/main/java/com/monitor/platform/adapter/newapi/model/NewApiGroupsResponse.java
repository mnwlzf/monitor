package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * New API 用户可用分组响应。
 *
 * <p>接口路径：{@code GET /api/user/self/groups}。{@code data} 的 key 为动态分组名，
 * 因此使用 Map 承载，避免新增分组时需要修改 Java 模型。</p>
 *
 * @param data    分组名到分组配置的映射
 * @param message 业务提示信息
 * @param success 是否请求成功
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiGroupsResponse(
        Map<String, Group> data,// key 为分组名称
        String message,
        boolean success
) {

    /**
     * 单个用户分组配置。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(
            @JsonProperty("base_ratio") Double baseRatio,
            String desc,//分组描述
            Integer order,
            Double ratio,//分组倍率
            @JsonProperty("schedule_active") Boolean scheduleActive,
            @JsonProperty("schedule_enabled") Boolean scheduleEnabled
    ) {
    }
}