package com.monitor.platform.pool.client;

/**
 * Sub2API 号池账号的明文凭证（仅内存中用于哈希比对，绝不落库）。
 *
 * @param name   号池账号名称
 * @param apiKey 上游 API Key 明文
 */
public record Sub2AdminAccountCredential(String name, String apiKey) {
}