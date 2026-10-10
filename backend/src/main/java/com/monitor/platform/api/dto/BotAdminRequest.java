package com.monitor.platform.api.dto;

/**
 * 新增自定义管理员的请求体。
 *
 * @param email  管理员邮箱（大小写不敏感）
 * @param remark 备注，便于多人协作时区分
 */
public record BotAdminRequest(String email, String remark) {
}