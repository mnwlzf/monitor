package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 自定义管理员（页面展示用）。
 *
 * @param id        主键
 * @param email     邮箱
 * @param remark    备注
 * @param createdAt 添加时间
 */
public record BotAdminResponse(Long id, String email, String remark, OffsetDateTime createdAt) {
}