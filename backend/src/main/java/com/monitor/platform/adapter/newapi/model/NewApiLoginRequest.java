package com.monitor.platform.adapter.newapi.model;

/**
 * New API 登录请求。
 *
 * @param username 登录用户名
 * @param password 登录密码
 */
public record NewApiLoginRequest(String username, String password) {
}