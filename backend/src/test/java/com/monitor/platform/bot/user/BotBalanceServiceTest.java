package com.monitor.platform.bot.user;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BotBalanceServiceTest {

    private static final Pattern CODE = Pattern.compile("确认码：([A-Z0-9]{8})");

    private final PlatformRepository platformRepository = mock(PlatformRepository.class);
    private final PoolAdminKeyResolver adminKeyResolver = mock(PoolAdminKeyResolver.class);
    private final Sub2UserAdminClient client = mock(Sub2UserAdminClient.class);
    private final PlatformEntity platform = new PlatformEntity();

    private BotBalanceService service;

    @BeforeEach
    void setUp() {
        platform.setId(1);
        platform.setUrl("https://sub2.example.com");
        platform.setStatus(true);
        platform.setPoolMonitoringEnabled(true);
        when(platformRepository.findAll()).thenReturn(List.of(platform));
        when(adminKeyResolver.resolve(platform)).thenReturn(Optional.of("admin-key"));
        service = new BotBalanceService(platformRepository, adminKeyResolver, client);
    }

    @Test
    void shouldAlwaysReturnCandidatesEvenForExactLookingUsername() {
        Sub2AdminUser alice = user(7L, "alice", "alice@example.com", 10.5);
        when(client.searchUsers("https://sub2.example.com", "admin-key", "alice", 20))
                .thenReturn(List.of(alice));

        String result = service.search("alice");

        assertTrue(result.contains("匹配到候选用户"));
        assertTrue(result.contains("ID 7"));
        assertTrue(result.contains("选择用户 <ID>"));
        assertTrue(!result.contains("确认码"));
    }

    @Test
    void shouldReturnCandidatesForRechargeAndNotCreatePreview() {
        when(client.searchUsers("https://sub2.example.com", "admin-key", "ali", 20))
                .thenReturn(List.of(
                        user(7L, "alice", "alice@example.com", 10.5),
                        user(8L, "alina", "alina@example.com", 8.0)));

        String result = service.candidatesForRecharge("ali", 5.0);

        assertTrue(result.contains("充值金额：$5"));
        assertTrue(result.contains("ID 7"));
        assertTrue(result.contains("ID 8"));
        assertTrue(result.contains("选择用户 <ID>"));
        assertTrue(!result.contains("确认码"));
        verify(client, never()).updateBalance(anyString(), anyString(), anyLong(),
                any(), any(), any());
    }

    @Test
    void shouldPreviewSelectedUserThenAddBalanceWithIdempotency() {
        Sub2AdminUser before = user(7L, "alice", "alice@example.com", 10.5);
        Sub2AdminUser after = user(7L, "alice", "alice@example.com", 23.0);
        when(client.findUserById("https://sub2.example.com", "admin-key", 7L))
                .thenReturn(Optional.of(before));
        when(client.updateBalance(eq("https://sub2.example.com"), eq("admin-key"), eq(7L),
                eq(new BigDecimal("12.50")), contains("admin@example.com"),
                anyString())).thenReturn(after);

        String preview = service.previewForUser(7L, 12.5, "支付波动补款", "admin@example.com");
        String code = code(preview);

        String result = service.confirm(code, "admin@example.com");

        assertTrue(result.contains("充值成功"));
        assertTrue(result.contains("充值前：$10.5"));
        assertTrue(result.contains("充值后：$23"));
        assertTrue(result.contains("累计充值：$100"));
        verify(client).updateBalance(eq("https://sub2.example.com"), eq("admin-key"), eq(7L),
                eq(new BigDecimal("12.50")), contains("支付波动补款"),
                eq("monitor-bot-balance-" + code));
    }

    @Test
    void shouldIncludeNumericIdAsCandidateWithoutAutoSelecting() {
        Sub2AdminUser found = user(9L, "bob", "bob@example.com", 3.0);
        when(client.searchUsers("https://sub2.example.com", "admin-key", "9", 20))
                .thenReturn(List.of());
        when(client.findUserById("https://sub2.example.com", "admin-key", 9L))
                .thenReturn(Optional.of(found));

        String result = service.search("9");

        assertTrue(result.contains("ID 9"));
        assertTrue(result.contains("选择用户 <ID>"));
        assertTrue(!result.contains("确认码"));
        verify(client).searchUsers("https://sub2.example.com", "admin-key", "9", 20);
        verify(client).findUserById("https://sub2.example.com", "admin-key", 9L);
    }

    @Test
    void shouldRejectNonPositiveOrMoreThanTwoDecimalPlaces() {
        assertTrue(service.candidatesForRecharge("alice", 0.0)
                .contains("必须大于 0"));
        assertTrue(service.candidatesForRecharge("alice", 1.234)
                .contains("最多保留两位小数"));
        verifyNoInteractions(client);
    }

    @Test
    void shouldRejectConfirmationFromAnotherAdmin() {
        Sub2AdminUser before = user(7L, "alice", "alice@example.com", 10.5);
        when(client.findUserById("https://sub2.example.com", "admin-key", 7L))
                .thenReturn(Optional.of(before));
        String code = code(service.previewForUser(7L, 5.0, null, "admin@example.com"));

        String result = service.confirm(code, "other@example.com");

        assertTrue(result.contains("不属于当前管理员"));
        verify(client, never()).updateBalance(anyString(), anyString(), anyLong(),
                any(), any(), any());
    }

    @Test
    void shouldReplayCompletedResultWithoutAddingTwice() {
        Sub2AdminUser before = user(7L, "alice", "alice@example.com", 10.5);
        Sub2AdminUser after = user(7L, "alice", "alice@example.com", 15.5);
        when(client.findUserById("https://sub2.example.com", "admin-key", 7L))
                .thenReturn(Optional.of(before));
        when(client.updateBalance(anyString(), anyString(), eq(7L), eq(new BigDecimal("5.00")),
                anyString(), anyString())).thenReturn(after);
        String code = code(service.previewForUser(7L, 5.0, null, "admin@example.com"));

        String first = service.confirm(code, "admin@example.com");
        String second = service.confirm(code, "admin@example.com");

        assertTrue(first.contains("充值成功"));
        assertTrue(second.contains("充值成功"));
        verify(client, times(1)).updateBalance(anyString(), anyString(), eq(7L),
                any(), any(), any());
    }

    private static Sub2AdminUser user(long id, String username, String email, double balance) {
        return new Sub2AdminUser(id, email, username, "user", balance, 0.0, 100.0,
                "active", null, null);
    }

    private static String code(String preview) {
        Matcher matcher = CODE.matcher(preview);
        if (!matcher.find()) {
            throw new AssertionError("preview did not contain confirmation code: " + preview);
        }
        return matcher.group(1);
    }
}
