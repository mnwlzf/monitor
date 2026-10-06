package com.monitor.platform.mail;

import com.monitor.platform.common.exception.BusinessException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * SMTP 邮件发送服务。
 *
 * <p>按 {@link SmtpConfig} 动态构建 {@link JavaMailSenderImpl}，因此页面修改配置后
 * 立即生效，无需重启。TLS 语义对齐 Sub2API：{@code useTls=true} 走隐式 TLS（465），
 * {@code useTls=false} 明文连接后机会式升级 STARTTLS（587/25）。</p>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** 建立连接超时，与 Sub2API 的 smtpDialTimeout 对齐。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 读写超时，与 Sub2API 的 smtpIOTimeout 对齐。 */
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 校验 SMTP 配置：建立一次连接后立即断开，用于页面「测试连接」。
     */
    public void testConnection(SmtpConfig config) {
        requireConfigured(config);
        try {
            buildSender(config).testConnection();
            log.info("SMTP 连接测试成功：host={}, port={}", config.host(), config.port());
        } catch (MessagingException e) {
            throw BusinessException.of("SMTP 连接失败：" + e.getMessage());
        }
    }

    /**
     * 发送 HTML 邮件给单个收件人。
     */
    public void send(SmtpConfig config, String to, String subject, String htmlBody) {
        send(config, List.of(to), subject, htmlBody);
    }

    /**
     * 发送 HTML 邮件给多个收件人（收件人互相可见）。
     *
     * @param config    已解密的 SMTP 配置
     * @param to        收件人地址列表
     * @param subject   邮件主题
     * @param htmlBody  邮件正文（HTML）
     */
    public void send(SmtpConfig config, List<String> to, String subject, String htmlBody) {
        requireConfigured(config);
        if (to == null || to.isEmpty()) {
            throw BusinessException.of("邮件收件人为空");
        }
        try {
            JavaMailSenderImpl sender = buildSender(config);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.resolveFrom(), config.fromName());
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            log.info("邮件发送成功：to={}, subject={}", to, subject);
        } catch (Exception e) {
            throw BusinessException.of("邮件发送失败：" + e.getMessage());
        }
    }

    private JavaMailSenderImpl buildSender(SmtpConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setDefaultEncoding("UTF-8");
        if (config.hasUsername()) {
            sender.setUsername(config.username());
            sender.setPassword(config.password());
        }

        Properties javaMail = sender.getJavaMailProperties();
        javaMail.put("mail.transport.protocol", "smtp");
        javaMail.put("mail.smtp.auth", String.valueOf(config.hasUsername()));
        javaMail.put("mail.smtp.ssl.enable", String.valueOf(config.useTls()));
        javaMail.put("mail.smtp.starttls.enable", String.valueOf(!config.useTls()));
        javaMail.put("mail.smtp.starttls.required", "false");
        javaMail.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT.toMillis()));
        javaMail.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT.toMillis()));
        javaMail.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT.toMillis()));
        return sender;
    }

    private void requireConfigured(SmtpConfig config) {
        if (config == null || !config.isConfigured()) {
            throw BusinessException.of("邮件未启用或未配置 SMTP 服务器地址");
        }
    }
}