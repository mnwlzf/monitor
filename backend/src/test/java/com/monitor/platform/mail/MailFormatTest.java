package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.common.time.BusinessTime;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件格式化工具的契约测试。
 *
 * <p>这些方法从多个告警服务里抽取而来，行为必须与抽取前一致：转义、时区、去引号、账号展示名。</p>
 */
class MailFormatTest {

    @Test
    void escapesHtmlAndNull() {
        assertThat(MailFormat.escape("<b>&\"")).isEqualTo("&lt;b&gt;&amp;&quot;");
        assertThat(MailFormat.escape(null)).isEmpty();
    }

    @Test
    void formatsTimeInBusinessZone() {
        // UTC 16:00 等于东八区次日 00:00
        OffsetDateTime utc = OffsetDateTime.parse("2026-10-09T16:00:00Z");
        assertThat(MailFormat.formatTime(utc)).isEqualTo("2026-10-10 00:00:00");
        assertThat(MailFormat.formatTime(null)).isEqualTo("—");
    }

    @Test
    void stripsQuotesFromScalarJson() {
        assertThat(MailFormat.scalar("\"active\"")).isEqualTo("active");
        assertThat(MailFormat.scalar(" 123 ")).isEqualTo("123");
        assertThat(MailFormat.scalar("null")).isNull();
        assertThat(MailFormat.scalar("")).isNull();
        assertThat(MailFormat.scalar(null)).isNull();
    }

    @Test
    void prefersDisplayNameThenUsernameThenId() {
        AccountEntity account = new AccountEntity();
        account.setId(7);
        account.setDisplayName("备注名");
        account.setUsername("user");
        assertThat(MailFormat.displayName(account)).isEqualTo("备注名");

        account.setDisplayName("  ");
        assertThat(MailFormat.displayName(account)).isEqualTo("user");

        account.setUsername(null);
        assertThat(MailFormat.displayName(account)).isEqualTo("账号 7");
    }

    @Test
    void businessZoneIsEastEight() {
        assertThat(BusinessTime.ZONE.getId()).isEqualTo("Asia/Shanghai");
        assertThat(BusinessTime.ZONE_ID).isEqualTo("Asia/Shanghai");
    }
}