package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.mail.MailService;
import com.monitor.platform.mail.SmtpConfigProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotEmailBindingServiceTest {

    @Test
    void shouldRenderSub2ApiStyleVerificationEmail() {
        String html = BotEmailBindingService.verificationEmailHtml("user@example.com", "123456");

        assertTrue(html.contains("邮箱绑定验证"));
        assertTrue(html.contains("123456"));
        assertTrue(html.contains("5 分钟"));
        assertTrue(html.contains("#0ea5e9"));
    }

    @Test
    void shouldRejectInvalidEmailBeforeSending() {
        BotEmailBindingService service = service();

        assertThrows(BusinessException.class, () -> service.requestBinding(999L, "not-an-email"));
    }

    @Test
    void shouldRejectEmailThatIsNotPlatformUser() {
        Sub2ApiUserCache cache = mock(Sub2ApiUserCache.class);
        when(cache.contains("user@example.com")).thenReturn(false);
        BotEmailBindingService service = service(cache);

        String reply = service.requestBinding(999L, "user@example.com");

        assertTrue(reply.contains("不是本平台注册用户"));
    }

    @Test
    void shouldEnforceSixtySecondResendCooldown() {
        BotUserBindingRepository repository = mock(BotUserBindingRepository.class);
        when(repository.findByExternalUser("QQ", "999")).thenReturn(Optional.empty());
        when(repository.findByEmail("QQ", "user@example.com")).thenReturn(Optional.empty());
        Sub2ApiUserCache cache = mock(Sub2ApiUserCache.class);
        when(cache.contains("user@example.com")).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("monitor:bot:bind:cooldown:999")).thenReturn(true);
        when(redis.getExpire("monitor:bot:bind:cooldown:999", TimeUnit.SECONDS)).thenReturn(42L);
        BotEmailBindingService service = new BotEmailBindingService(
                repository,
                mock(QqUserBindingService.class),
                cache,
                mock(SmtpConfigProvider.class),
                mock(MailService.class),
                redis,
                mock(PasswordEncoder.class));

        String reply = service.requestBinding(999L, "user@example.com");

        assertEquals("发送太频繁，请 42 秒后再试。", reply);
    }

    private static BotEmailBindingService service() {
        Sub2ApiUserCache cache = mock(Sub2ApiUserCache.class);
        when(cache.contains("user@example.com")).thenReturn(true);
        return service(cache);
    }

    private static BotEmailBindingService service(Sub2ApiUserCache cache) {
        BotUserBindingRepository repository = mock(BotUserBindingRepository.class);
        when(repository.findByExternalUser("QQ", "999")).thenReturn(Optional.empty());
        when(repository.findByEmail("QQ", "user@example.com")).thenReturn(Optional.empty());
        return new BotEmailBindingService(
                repository,
                mock(QqUserBindingService.class),
                cache,
                mock(SmtpConfigProvider.class),
                mock(MailService.class),
                mock(StringRedisTemplate.class),
                mock(PasswordEncoder.class));
    }
}
