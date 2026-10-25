package com.monitor.platform.bot.report;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotGroupRedactorTest {

    @Test
    void redactsPlatformNamesAccountNamesUrlsAndEmails() {
        PlatformRepository platformRepository = mock(PlatformRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);

        PlatformEntity platform1 = platform(1, "云锦", "https://yunmian.tech");
        PlatformEntity platform2 = platform(2, "MagicAI", "https://ai.magic.test");
        AccountEntity account1 = account(10, "lc", "lc-user", "lc@example.com");
        AccountEntity account2 = account(11, "sail", "sail-user", "sail@example.com");

        when(platformRepository.findAll()).thenReturn(List.of(platform1, platform2));
        when(accountRepository.findByPlatformId(1)).thenReturn(List.of(account1));
        when(accountRepository.findByPlatformId(2)).thenReturn(List.of(account2));

        BotGroupRedactor redactor = new BotGroupRedactor(platformRepository, accountRepository);
        BotReport report = BotReport.fromMarkdown("""
                云锦 / MagicAI 概览
                - 云锦账号 lc，余额 $10，邮箱 lc@example.com
                - MagicAI账号 sail，地址 https://ai.magic.test，账号 11，账号11
                - 未知邮箱 other@example.org
                - selected 这个词不应被误替换
                """);

        String text = redactor.redact(report).toPlainText();

        assertThat(text)
                .contains("上游平台1")
                .contains("上游平台2")
                .contains("上游账号1")
                .contains("上游账号2")
                .contains("selected")
                .contains("[邮箱已隐藏]")
                .doesNotContain("云锦")
                .doesNotContain("MagicAI")
                .doesNotContain("yunmian.tech")
                .doesNotContain("ai.magic.test")
                .doesNotContain("lc-user")
                .doesNotContain("sail-user")
                .doesNotContain("lc@example.com")
                .doesNotContain("sail@example.com")
                .doesNotContain("账号11");
    }

    private static PlatformEntity platform(Integer id, String name, String url) {
        PlatformEntity platform = new PlatformEntity();
        platform.setId(id);
        platform.setPlatformName(name);
        platform.setUrl(url);
        return platform;
    }

    private static AccountEntity account(Integer id, String displayName, String username, String email) {
        AccountEntity account = new AccountEntity();
        account.setId(id);
        account.setPlatformId(1);
        account.setDisplayName(displayName);
        account.setUsername(username);
        account.setEmail(email);
        return account;
    }
}