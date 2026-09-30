package com.monitor.platform.adapter.sub2api.model;

/**
 * Sub2API 登录请求。
 *
 * @param email    登录邮箱
 * @param password 登录密码
 */
public record Sub2LoginRequest(String email, String password) {
}