package com.monitor.platform.adapter.sub2api;

import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginRequest;
import com.monitor.platform.adapter.sub2api.model.Sub2LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sub2ApiAdapter 可用分组编排测试。
 */
@ExtendWith(MockitoExtension.class)
class Sub2ApiAdapterTest {

    private static final String BASE_URL = "https://codex.trovebox.online";
    private static final String EMAIL = "user@example.com";
    private static final String TOKEN_CACHE_KEY = "sub2:token:" + BASE_URL + ":" + EMAIL;

    @Mock
    private Sub2ApiClient sub2ApiClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private Sub2ApiAdapter adapter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        adapter = new Sub2ApiAdapter(sub2ApiClient, stringRedisTemplate);
    }

    @Test
    void shouldFetchAvailableGroupsUsingCachedToken() {
        Sub2GroupsResponse response = new Sub2GroupsResponse(0, "success", List.of());
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn("cached-token");
        when(sub2ApiClient.fetchAvailableGroups(BASE_URL, "cached-token")).thenReturn(response);

        Sub2GroupsResponse result = adapter.fetchAvailableGroups(BASE_URL, EMAIL);

        assertSame(response, result);
        verify(sub2ApiClient).fetchAvailableGroups(BASE_URL, "cached-token");
    }

    @Test
    void shouldLoginThenFetchAvailableGroupsWhenTokenIsMissing() {
        Sub2LoginResponse loginResponse = new Sub2LoginResponse(
                0,
                "success",
                new Sub2LoginResponse.LoginData(
                        "access-token",
                        "refresh-token",
                        3600,
                        "Bearer",
                        null
                )
        );
        Sub2GroupsResponse groupsResponse = new Sub2GroupsResponse(0, "success", List.of());

        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn(null, null, "access-token");
        when(sub2ApiClient.login(eq(BASE_URL), org.mockito.ArgumentMatchers.any(Sub2LoginRequest.class)))
                .thenReturn(loginResponse);
        when(sub2ApiClient.fetchAvailableGroups(BASE_URL, "access-token"))
                .thenReturn(groupsResponse);

        Sub2GroupsResponse result = adapter.fetchAvailableGroups(BASE_URL, EMAIL);

        assertSame(groupsResponse, result);
        ArgumentCaptor<Sub2LoginRequest> requestCaptor = ArgumentCaptor.forClass(Sub2LoginRequest.class);
        verify(sub2ApiClient).login(eq(BASE_URL), requestCaptor.capture());
        assertEquals(EMAIL, requestCaptor.getValue().email());
        assertEquals("test", requestCaptor.getValue().password());
        verify(valueOperations).set(eq(TOKEN_CACHE_KEY), eq("access-token"), eq(Duration.ofDays(1)));
        verify(sub2ApiClient).fetchAvailableGroups(BASE_URL, "access-token");
    }
}