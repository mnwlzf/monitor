package com.monitor.platform.bot.identity;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Sub2ApiUserCacheTest {

    @Test
    void shouldReturnOnlyAdminEmailsFromCachedRoles() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.entries(Sub2ApiUserCache.USERS_KEY)).thenReturn(Map.of(
                "admin@example.com", "admin",
                "user@example.com", "user"));

        List<String> admins = new Sub2ApiUserCache(redis).adminEmails();

        assertEquals(List.of("admin@example.com"), admins);
    }
}
