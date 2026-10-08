package com.monitor.platform.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 号池账号类型归一化测试：只把 API Key 类型当作需要监控的账号。
 */
class PoolAccountTypesTest {

    @Test
    void shouldRecognizeApiKeyRegardlessOfFormat() {
        assertTrue(PoolAccountTypes.isApiKey("apikey"));
        assertTrue(PoolAccountTypes.isApiKey("APIKEY"));
        assertTrue(PoolAccountTypes.isApiKey("api_key"));
        assertTrue(PoolAccountTypes.isApiKey("api-key"));
        assertTrue(PoolAccountTypes.isApiKey(" Api-Key "));
    }

    @Test
    void shouldIgnoreOtherTypes() {
        assertFalse(PoolAccountTypes.isApiKey("oauth"));
        assertFalse(PoolAccountTypes.isApiKey("cookie"));
        assertFalse(PoolAccountTypes.isApiKey("apikey-oauth"));
        assertFalse(PoolAccountTypes.isApiKey(null));
        assertFalse(PoolAccountTypes.isApiKey("   "));
    }

    @Test
    void shouldNormalizeOrReturnNullForBlank() {
        assertEquals("apikey", PoolAccountTypes.normalize("Api Key"));
        assertEquals("oauth", PoolAccountTypes.normalize("OAUTH"));
        assertNull(PoolAccountTypes.normalize(null));
        assertNull(PoolAccountTypes.normalize(" - _ "));
    }
}