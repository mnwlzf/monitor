package com.monitor.platform.bot.identity;

import com.monitor.platform.bot.BotAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 身份判定：绑定 / 自动匹配、Sub2API 角色与自定义管理员的并集。
 */
class BotIdentityResolverTest {

    private static final long QQ = 2755457558L;
    private static final String EMAIL = "2755457558@qq.com";

    private BotIdentityProperties properties;
    private QqUserBindingService bindings;
    private Sub2ApiUserCache cache;
    private BotAdminService botAdmins;
    private BotIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new BotIdentityProperties();
        bindings = mock(QqUserBindingService.class);
        cache = mock(Sub2ApiUserCache.class);
        botAdmins = mock(BotAdminService.class);
        resolver = new BotIdentityResolver(properties, bindings, cache, botAdmins);
    }

    @Test
    void shouldReturnGuestWhenNothingMatches() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.empty());
        when(cache.emailByQq(QQ)).thenReturn(Optional.empty());

        assertEquals(BotIdentity.guest(), resolver.resolve(QQ));
    }

    @Test
    void shouldReturnGuestWhenEmailIsNotPlatformUserNorCustomAdmin() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.of("stranger@example.com"));
        when(cache.contains("stranger@example.com")).thenReturn(false);
        when(botAdmins.isAdmin("stranger@example.com")).thenReturn(false);

        assertFalse(resolver.resolve(QQ).platformUser());
    }

    @Test
    void shouldRecognizeSub2ApiAdminByAutoMatch() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.empty());
        when(cache.emailByQq(QQ)).thenReturn(Optional.of(EMAIL));
        when(cache.contains(EMAIL)).thenReturn(true);
        when(cache.isAdmin(EMAIL)).thenReturn(true);

        BotIdentity identity = resolver.resolve(QQ);
        assertTrue(identity.platformUser());
        assertTrue(identity.admin());
        assertEquals(EMAIL, identity.email());
    }

    @Test
    void shouldRecognizeNormalUser() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.of(EMAIL));
        when(cache.contains(EMAIL)).thenReturn(true);
        when(cache.isAdmin(EMAIL)).thenReturn(false);
        when(botAdmins.isAdmin(EMAIL)).thenReturn(false);

        BotIdentity identity = resolver.resolve(QQ);
        assertTrue(identity.platformUser());
        assertFalse(identity.admin());
    }

    /** 自定义管理员即使不是 Sub2API 用户，也应当拿到管理员身份。 */
    @Test
    void shouldRecognizeCustomAdminWhoIsNotSub2ApiUser() {
        String custom = "ops@example.com";
        when(bindings.emailOf(QQ)).thenReturn(Optional.of(custom));
        when(cache.contains(custom)).thenReturn(false);
        when(botAdmins.isAdmin(custom)).thenReturn(true);

        BotIdentity identity = resolver.resolve(QQ);
        assertTrue(identity.platformUser());
        assertTrue(identity.admin());
    }

    /** 两种来源取并集：Sub2API 普通用户 + 自定义管理员 = 管理员。 */
    @Test
    void shouldUnionSub2ApiRoleAndCustomAdmin() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.of(EMAIL));
        when(cache.contains(EMAIL)).thenReturn(true);
        when(cache.isAdmin(EMAIL)).thenReturn(false);
        when(botAdmins.isAdmin(EMAIL)).thenReturn(true);

        assertTrue(resolver.resolve(QQ).admin());
    }

    /** 显式绑定优先于 QQ 号自动匹配。 */
    @Test
    void shouldPreferBindingOverAutoMatch() {
        when(bindings.emailOf(QQ)).thenReturn(Optional.of("bound@example.com"));
        when(cache.contains("bound@example.com")).thenReturn(true);
        when(cache.isAdmin("bound@example.com")).thenReturn(false);

        assertEquals("bound@example.com", resolver.resolve(QQ).email());
    }

    @Test
    void shouldReturnGuestWhenDisabled() {
        properties.setEnabled(false);

        assertEquals(BotIdentity.guest(), resolver.resolve(QQ));
    }

    @Test
    void shouldNotAutoMatchWhenDisabled() {
        properties.setQqLocalPartMatch(false);
        when(bindings.emailOf(QQ)).thenReturn(Optional.empty());

        assertEquals(BotIdentity.guest(), resolver.resolve(QQ));
    }

    @Test
    void shouldReturnGuestForNullQq() {
        assertEquals(BotIdentity.guest(), resolver.resolve(null));
    }
}