package com.monitor.platform.adapter.newapi;

import com.monitor.platform.adapter.newapi.model.NewApiGroupsResponse;
import com.monitor.platform.adapter.newapi.model.NewApiLoginRequest;
import com.monitor.platform.adapter.newapi.model.NewApiLoginResponse;
import com.monitor.platform.adapter.newapi.model.NewApiUser;
import com.monitor.platform.adapter.newapi.model.NewApiSelfResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NewApiAdapter 登录和分组编排测试。
 */
@ExtendWith(MockitoExtension.class)
class NewApiAdapterTest {

    private static final String BASE_URL = "https://yunmian.tech";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "password";
    private static final String TOKEN_CACHE_KEY = "newapi:token:" + BASE_URL + ":" + USERNAME;
    private static final String USER_ID_CACHE_KEY = "newapi:user-id:" + BASE_URL + ":" + USERNAME;
    private static final String REFRESH_CACHE_KEY = "newapi:refresh-token:" + BASE_URL + ":" + USERNAME;

    @Mock
    private NewApiClient newApiClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private NewApiAdapter adapter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        adapter = new NewApiAdapter(newApiClient, stringRedisTemplate);
    }

    @Test
    void shouldCacheTokenAndUserIdUsingUpstreamExpiry() {
        long expiresAt = Instant.now().getEpochSecond() + 900;
        NewApiLoginResponse response = successResponse(expiresAt);
        when(newApiClient.login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), ""))
                .thenReturn(new NewApiClient.AuthSession(response, "refresh-token"));

        adapter.login(BASE_URL, USERNAME, PASSWORD);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(TOKEN_CACHE_KEY), eq("access-token"), ttlCaptor.capture());
        verify(valueOperations).set(eq(USER_ID_CACHE_KEY), eq("647"), ttlCaptor.capture());
        verify(valueOperations).set(eq(REFRESH_CACHE_KEY), eq("refresh-token"), ttlCaptor.capture());

        List<Duration> ttls = ttlCaptor.getAllValues();
        assertTrue(ttls.get(0).getSeconds() > 0);
        assertTrue(ttls.get(0).getSeconds() <= 870);
        assertTrue(ttls.get(1).equals(ttls.get(0)));
    }

    @Test
    void shouldReuseCompleteCachedSession() {
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn("cached-token");
        when(valueOperations.get(USER_ID_CACHE_KEY)).thenReturn("647");

        adapter.login(BASE_URL, USERNAME, PASSWORD);

        verifyNoInteractions(newApiClient);
    }

    @Test
    void shouldRejectFailedLoginResponse() {
        NewApiLoginResponse response = new NewApiLoginResponse(
                null,
                "invalid credentials",
                false
        );
        when(newApiClient.login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), ""))
                .thenReturn(new NewApiClient.AuthSession(response, null));

        assertThrows(IllegalStateException.class,
                () -> adapter.login(BASE_URL, USERNAME, PASSWORD));
    }

    @Test
    void shouldRefreshAccessTokenInsteadOfLoggingInWhenRefreshTokenExists() {
        long expiresAt = Instant.now().getEpochSecond() + 900;
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn(null, "refreshed-token");
        when(valueOperations.get(REFRESH_CACHE_KEY)).thenReturn("refresh-token");
        when(newApiClient.refreshAuth(BASE_URL, "refresh-token"))
                .thenReturn(new NewApiClient.AuthSession(successResponse(expiresAt), "rotated-refresh-token"));
        when(newApiClient.fetchGroups(BASE_URL, "refreshed-token")).thenReturn(groupsResponse());

        adapter.fetchGroups(BASE_URL, USERNAME, PASSWORD);

        verify(newApiClient, never()).login(any(), any(), any());
        verify(newApiClient).refreshAuth(BASE_URL, "refresh-token");
        verify(valueOperations).set(eq(REFRESH_CACHE_KEY), eq("rotated-refresh-token"), any(Duration.class));
        verify(newApiClient).fetchGroups(BASE_URL, "refreshed-token");
    }

    @Test
    void shouldKeepCachedUserIdWhenRefreshResponseOmitsUser() {
        long expiresAt = Instant.now().getEpochSecond() + 900;
        NewApiLoginResponse withoutUser = new NewApiLoginResponse(
                new NewApiLoginResponse.LoginData(expiresAt, "refreshed-token", null, "Bearer", null),
                "",
                true
        );
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn(null, "refreshed-token");
        when(valueOperations.get(REFRESH_CACHE_KEY)).thenReturn("refresh-token");
        when(valueOperations.get(USER_ID_CACHE_KEY)).thenReturn("647");
        when(newApiClient.refreshAuth(BASE_URL, "refresh-token"))
                .thenReturn(new NewApiClient.AuthSession(withoutUser, "rotated-refresh-token"));
        when(newApiClient.fetchGroups(BASE_URL, "refreshed-token")).thenReturn(groupsResponse());

        adapter.fetchGroups(BASE_URL, USERNAME, PASSWORD);

        verify(newApiClient, never()).login(any(), any(), any());
        verify(valueOperations).set(eq(USER_ID_CACHE_KEY), eq("647"), any(Duration.class));
    }

    @Test
    void shouldFallBackToLoginWhenRefreshTokenIsRejected() {
        long expiresAt = Instant.now().getEpochSecond() + 900;
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn(null, "access-token");
        when(valueOperations.get(REFRESH_CACHE_KEY)).thenReturn("stale-refresh-token");
        when(newApiClient.refreshAuth(BASE_URL, "stale-refresh-token"))
                .thenThrow(new RestClientResponseException("unauthorized", 401, "Unauthorized", null, null, null));
        when(newApiClient.login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), ""))
                .thenReturn(new NewApiClient.AuthSession(successResponse(expiresAt), "refresh-token"));
        when(newApiClient.fetchGroups(BASE_URL, "access-token")).thenReturn(groupsResponse());

        adapter.fetchGroups(BASE_URL, USERNAME, PASSWORD);

        verify(newApiClient).refreshAuth(BASE_URL, "stale-refresh-token");
        verify(newApiClient).login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), "");
        verify(newApiClient).fetchGroups(BASE_URL, "access-token");
    }

    @Test
    void shouldFetchGroupsUsingCachedToken() {
        NewApiGroupsResponse groups = groupsResponse();
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn("cached-token");
        when(newApiClient.fetchGroups(BASE_URL, "cached-token")).thenReturn(groups);

        NewApiGroupsResponse result = adapter.fetchGroups(BASE_URL, USERNAME, PASSWORD);

        assertSame(groups, result);
        verify(newApiClient).fetchGroups(BASE_URL, "cached-token");
    }

    @Test
    void shouldLoginThenFetchGroupsWhenTokenIsMissing() {
        long expiresAt = Instant.now().getEpochSecond() + 900;
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn(null, "access-token");
        when(valueOperations.get(USER_ID_CACHE_KEY)).thenReturn(null);
        when(valueOperations.get(REFRESH_CACHE_KEY)).thenReturn(null);
        when(newApiClient.login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), ""))
                .thenReturn(new NewApiClient.AuthSession(successResponse(expiresAt), "refresh-token"));
        when(newApiClient.fetchGroups(BASE_URL, "access-token")).thenReturn(groupsResponse());

        adapter.fetchGroups(BASE_URL, USERNAME, PASSWORD);

        verify(newApiClient).login(BASE_URL, new NewApiLoginRequest(USERNAME, PASSWORD), "");
        verify(newApiClient).fetchGroups(BASE_URL, "access-token");
    }

    private NewApiLoginResponse successResponse(long expiresAt) {
        NewApiUser user = new NewApiUser(
                647L,
                USERNAME,
                USERNAME,
                USERNAME,
                "default",
                1,
                1,
                null,
                null,
                null
        );
        NewApiLoginResponse.LoginData data = new NewApiLoginResponse.LoginData(
                expiresAt,
                "access-token",
                null,
                "Bearer",
                user
        );
        return new NewApiLoginResponse(data, "", true);
    }

    private NewApiGroupsResponse groupsResponse() {
        NewApiGroupsResponse.Group group = new NewApiGroupsResponse.Group(
                0.12,
                "特价分组",
                10,
                0.12,
                false,
                false
        );
        return new NewApiGroupsResponse(Map.of("codex-特价", group), "", true);
    }
    @Test
    void shouldFetchSelfUsingCachedToken() {
        NewApiUser user = new NewApiUser(
                647L,
                USERNAME,
                USERNAME,
                USERNAME,
                "default",
                1,
                1,
                1467141L,
                70135359L,
                33374L
        );
        NewApiSelfResponse selfResponse = new NewApiSelfResponse(user, "", true);
        when(valueOperations.get(TOKEN_CACHE_KEY)).thenReturn("cached-token");
        when(newApiClient.fetchSelf(BASE_URL, "cached-token")).thenReturn(selfResponse);

        NewApiSelfResponse result = adapter.fetchSelf(BASE_URL, USERNAME, PASSWORD);

        assertSame(selfResponse, result);
        verify(newApiClient).fetchSelf(BASE_URL, "cached-token");
    }
}
