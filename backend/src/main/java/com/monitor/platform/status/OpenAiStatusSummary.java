package com.monitor.platform.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Statuspage 的 summary.json 响应。
 *
 * <p>只声明机器人展示和告警判断需要的字段；状态页以后增加字段不会导致解析失败。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiStatusSummary(
        Page page,
        Status status,
        List<Component> components,
        List<Incident> incidents
) {

    public OpenAiStatusSummary {
        components = components == null ? List.of() : List.copyOf(components);
        incidents = incidents == null ? List.of() : List.copyOf(incidents);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(
            String name,
            String url,
            @JsonProperty("updated_at") String updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            String description,
            String indicator
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(
            String name,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Incident(
            String name,
            String status,
            String impact,
            String shortlink,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt
    ) {
    }
}