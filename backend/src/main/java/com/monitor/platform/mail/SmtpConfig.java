package com.monitor.platform.mail;

/**
 * 一次邮件发送所需的 SMTP 配置快照（含解密后的明文密码）。
 *
 * <p>字段与 Sub2API 的 {@code SMTPConfig} 对齐：host / port / username / password /
 * from / fromName / useTls。</p>
 */
public record SmtpConfig(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        String from,
        String fromName,
        boolean useTls
) {

    /** 是否已启用且配置了服务器地址。 */
    public boolean isConfigured() {
        return enabled && host != null && !host.isBlank();
    }

    /** 是否配置了登录用户名。 */
    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    /** 解析最终发件人地址：优先 from，其次 username。 */
    public String resolveFrom() {
        if (from != null && !from.isBlank()) {
            return from;
        }
        return username;
    }
}