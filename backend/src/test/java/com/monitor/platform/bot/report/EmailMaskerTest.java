package com.monitor.platform.bot.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @Test
    void shouldKeepPrefixAndSuffixWhileMaskingMiddle() {
        assertThat(EmailMasker.maskAddress("user@example.com")).isEqualTo("use**********com");
        assertThat(EmailMasker.maskAddress("ab@c.io")).isEqualTo("ab***io");
    }

    @Test
    void shouldMaskEveryEmailInReport() {
        BotReport report = BotReport.fromMarkdown("""
                用户 user@example.com
                另一个 other@example.org
                """);

        String text = EmailMasker.maskReport(report).toPlainText();

        assertThat(text)
                .contains("***")
                .doesNotContain("user@example.com")
                .doesNotContain("other@example.org");
    }
}
