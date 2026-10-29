package com.monitor.platform.api.dto;

/** 自愈执行结果响应。 */
public record OpsActionExecutionResponse(boolean success, String message, OpsActionPlanResponse plan) {
}