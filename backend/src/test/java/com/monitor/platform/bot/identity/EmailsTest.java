package com.monitor.platform.bot.identity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮箱规范化 / 校验 / QQ 号提取。
 *
 * <p>这套规则是身份识别的地基：缓存、绑定、自定义管理员三处必须完全一致，
 * 否则会出现「明明在名单里却认不出来」的问题。</p>
 */
class EmailsTest {

    @Test
    void shouldNormalizeTrimAndLowerCase() {
        assertEquals(Optional.of("a@b.com"), Emails.normalize("  A@B.CoM  "));
    }

    @Test
    void shouldTreatNullAndBlankAsEmpty() {
        assertEquals(Optional.empty(), Emails.normalize(null));
        assertEquals(Optional.empty(), Emails.normalize("   "));
    }

    @Test
    void shouldValidateFormat() {
        assertTrue(Emails.isValid("2755457558@qq.com"));
        assertFalse(Emails.isValid("not-an-email"));
        assertFalse(Emails.isValid("a@b"));
        assertFalse(Emails.isValid(null));
        assertFalse(Emails.isValid(""));
    }

    @Test
    void shouldExtractQqLocalPart() {
        assertEquals(Optional.of("2755457558"), Emails.qqLocalPart("2755457558@qq.com"));
        // 大小写与空格不影响
        assertEquals(Optional.of("12345"), Emails.qqLocalPart(" 12345@QQ.com "));
    }

    @Test
    void shouldNotExtractQqFromNonQqMailbox() {
        assertEquals(Optional.empty(), Emails.qqLocalPart("someone@example.com"));
        // 位数不足 5 位时不当成 QQ 号
        assertEquals(Optional.empty(), Emails.qqLocalPart("123@qq.com"));
    }
}