package com.monitor.platform.bot.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotBalanceToolsTest {

    private final BotBalanceService service = mock(BotBalanceService.class);

    @Test
    void shouldDelegateSearchAndPreview() {
        when(service.search("alice")).thenReturn("candidates");
        when(service.candidatesForRecharge("alice", 10.0)).thenReturn("candidates-with-amount");
        when(service.previewForUser(7L, 10.0, "补款", "admin@example.com")).thenReturn("preview");

        BotBalanceTools tools = new BotBalanceTools(service, "admin@example.com", "给 alice 充值 10");
        assertEquals("candidates", tools.searchPlatformUsers("alice"));
        assertEquals("candidates-with-amount", tools.findBalanceRechargeCandidates("alice", 10.0));
        assertEquals("preview", tools.previewBalanceIncreaseForUser(7L, 10.0, "补款"));
    }

    @Test
    void shouldRejectConfirmationWithoutExplicitPhraseAndCode() {
        BotBalanceTools tools = new BotBalanceTools(service, "admin@example.com", "确认充值");

        String result = tools.confirmBalanceIncrease("ABCD1234");

        assertTrue(result.contains("二次确认未通过"));
        verify(service, never()).confirm("ABCD1234", "admin@example.com");
    }

    @Test
    void shouldConfirmOnlyWhenCurrentMessageContainsPhraseAndCode() {
        when(service.confirm("ABCD1234", "admin@example.com")).thenReturn("ok");
        BotBalanceTools tools = new BotBalanceTools(service, "admin@example.com",
                "确认充值 abcd1234");

        String result = tools.confirmBalanceIncrease("abcd1234");

        assertEquals("ok", result);
        verify(service).confirm("ABCD1234", "admin@example.com");
    }
}
