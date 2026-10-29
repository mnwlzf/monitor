package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.Emails;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.mail.MailFormat;
import com.monitor.platform.mail.MailService;
import com.monitor.platform.mail.SmtpConfig;
import com.monitor.platform.mail.SmtpConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * QQ 机器人邮箱验证绑定。
 *
 * <p>用户在群聊或私聊发起绑定后，系统向平台注册邮箱发送 6 位验证码。
 * 验证码 5 分钟有效，同一 QQ 60 秒内不可重复发送，每小时最多发送 5 次，
 * 同一邮箱每小时最多接收 5 次。</p>
 */
@Service
public class BotEmailBindingService {

    private static final Logger log = LoggerFactory.getLogger(BotEmailBindingService.class);
    public static final String CHANNEL_QQ = "QQ";
    public static final String METHOD_EMAIL_CODE = "EMAIL_CODE";
    public static final String METHOD_QQ_LOCAL_PART = "QQ_LOCAL_PART";

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    static final Duration HOURLY_WINDOW = Duration.ofHours(1);
    static final int MAX_SENDS_PER_HOUR = 5;
    static final int MAX_ATTEMPTS = 5;

    private static final String CODE_KEY_PREFIX = "monitor:bot:bind:code:";
    private static final String COOLDOWN_KEY_PREFIX = "monitor:bot:bind:cooldown:";
    private static final String QQ_HOURLY_KEY_PREFIX = "monitor:bot:bind:hourly:qq:";
    private static final String EMAIL_HOURLY_KEY_PREFIX = "monitor:bot:bind:hourly:email:";
    private static final String ADMIN_UNBIND_KEY_PREFIX = "monitor:bot:unbind:confirm:";
    private static final Duration ADMIN_UNBIND_TTL = Duration.ofMinutes(5);
    private static final String CONFIRM_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BotUserBindingRepository repository;
    private final QqUserBindingService legacyBindings;
    private final Sub2ApiUserCache userCache;
    private final SmtpConfigProvider smtpConfigProvider;
    private final MailService mailService;
    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;

    public BotEmailBindingService(BotUserBindingRepository repository,
                                  QqUserBindingService legacyBindings,
                                  Sub2ApiUserCache userCache,
                                  SmtpConfigProvider smtpConfigProvider,
                                  MailService mailService,
                                  StringRedisTemplate redis,
                                  PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.legacyBindings = legacyBindings;
        this.userCache = userCache;
        this.smtpConfigProvider = smtpConfigProvider;
        this.mailService = mailService;
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
    }

    /** 发起绑定并发送验证码。 */
    public String requestBinding(Long qq, String rawEmail) {
        if (qq == null) {
            return "无法识别当前 QQ，暂时不能绑定邮箱。";
        }
        String email = Emails.normalize(rawEmail)
                .orElseThrow(() -> BusinessException.of("请提供要绑定的邮箱"));
        if (!Emails.isValid(email)) {
            throw BusinessException.of("邮箱格式不正确：" + rawEmail);
        }
        if (!userCache.contains(email)) {
            return "该邮箱不是本平台注册用户，请先注册或联系管理员。";
        }

        Optional<BotUserBindingEntity> sameUser = repository.findByExternalUser(CHANNEL_QQ, String.valueOf(qq));
        if (sameUser.isPresent() && email.equals(sameUser.get().getEmail())) {
            return "你已经绑定了该邮箱，无需重复验证。";
        }
        Optional<BotUserBindingEntity> sameEmail = repository.findByEmail(CHANNEL_QQ, email);
        if (sameEmail.isPresent() && !String.valueOf(qq).equals(sameEmail.get().getExternalUserId())) {
            return "该邮箱已经绑定了其他 QQ，不能重复绑定。";
        }

        String cooldownKey = COOLDOWN_KEY_PREFIX + qq;
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
            Long seconds = redis.getExpire(cooldownKey, TimeUnit.SECONDS);
            long wait = seconds == null || seconds <= 0 ? RESEND_COOLDOWN.toSeconds() : seconds;
            return "发送太频繁，请 " + wait + " 秒后再试。";
        }

        String qqHourlyKey = QQ_HOURLY_KEY_PREFIX + qq;
        if (counter(qqHourlyKey) >= MAX_SENDS_PER_HOUR) {
            return "当前 QQ 一小时内最多发送 5 次验证码，请稍后再试。";
        }
        String emailHourlyKey = EMAIL_HOURLY_KEY_PREFIX + email;
        if (counter(emailHourlyKey) >= MAX_SENDS_PER_HOUR) {
            return "该邮箱一小时内接收验证码次数已达上限，请稍后再试。";
        }

        Boolean locked = redis.opsForValue().setIfAbsent(cooldownKey, "1", RESEND_COOLDOWN);
        if (!Boolean.TRUE.equals(locked)) {
            Long seconds = redis.getExpire(cooldownKey, TimeUnit.SECONDS);
            return "发送太频繁，请 " + (seconds == null || seconds <= 0 ? 60 : seconds) + " 秒后再试。";
        }

        Long qqCount = incrementWithTtl(qqHourlyKey, HOURLY_WINDOW);
        Long emailCount = incrementWithTtl(emailHourlyKey, HOURLY_WINDOW);
        if ((qqCount != null && qqCount > MAX_SENDS_PER_HOUR)
                || (emailCount != null && emailCount > MAX_SENDS_PER_HOUR)) {
            releaseQuota(qqHourlyKey, emailHourlyKey, cooldownKey);
            return qqCount != null && qqCount > MAX_SENDS_PER_HOUR
                    ? "当前 QQ 一小时内最多发送 5 次验证码，请稍后再试。"
                    : "该邮箱一小时内接收验证码次数已达上限，请稍后再试。";
        }

        String code = generateCode();
        try {
            SmtpConfig config = smtpConfigProvider.load();
            mailService.send(config, email, "[Monitor] 邮箱绑定验证码", verificationEmailHtml(email, code));
        } catch (RuntimeException ex) {
            releaseQuota(qqHourlyKey, emailHourlyKey, cooldownKey);
            redis.delete(codeKey(qq));
            throw ex;
        }

        Map<String, String> pending = new LinkedHashMap<>();
        pending.put("email", email);
        pending.put("codeHash", passwordEncoder.encode(code));
        pending.put("attempts", "0");
        String codeKey = codeKey(qq);
        redis.opsForHash().putAll(codeKey, pending);
        redis.expire(codeKey, CODE_TTL);
        log.info("已发送 QQ 机器人邮箱绑定验证码: qq={}, email={}", qq, email);
        return "验证码已发送至 " + email + "，5 分钟内有效。\n"
                + "请发送「/验证 <验证码>」，或在待验证状态下直接发送 6 位验证码。";
    }

    /** 使用验证码完成绑定。 */
    public String verify(Long qq, String rawCode) {
        if (qq == null) {
            return "无法识别当前 QQ，暂时不能验证邮箱。";
        }
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{6}")) {
            return "请输入 6 位数字验证码。";
        }
        String codeKey = codeKey(qq);
        Map<Object, Object> pending = redis.opsForHash().entries(codeKey);
        if (pending == null || pending.isEmpty()) {
            return "没有待验证的邮箱，请先发送「/绑定 <邮箱>」。";
        }
        String email = String.valueOf(pending.get("email"));
        String codeHash = String.valueOf(pending.get("codeHash"));
        int attempts = parseAttempts(pending.get("attempts"));
        if (attempts >= MAX_ATTEMPTS) {
            redis.delete(codeKey);
            return "验证码错误次数过多，请重新发送「/绑定 <邮箱>」获取新验证码。";
        }
        if (!passwordEncoder.matches(code, codeHash)) {
            attempts++;
            if (attempts >= MAX_ATTEMPTS) {
                redis.delete(codeKey);
                return "验证码错误次数过多，本次验证码已失效，请重新获取。";
            }
            redis.opsForHash().put(codeKey, "attempts", String.valueOf(attempts));
            return "验证码不正确，还可尝试 " + (MAX_ATTEMPTS - attempts) + " 次。";
        }
        if (!userCache.contains(email)) {
            redis.delete(codeKey);
            return "该邮箱已经不是平台注册用户，绑定已取消。";
        }
        Optional<BotUserBindingEntity> sameEmail = repository.findByEmail(CHANNEL_QQ, email);
        if (sameEmail.isPresent() && !String.valueOf(qq).equals(sameEmail.get().getExternalUserId())) {
            redis.delete(codeKey);
            return "该邮箱已经绑定了其他 QQ，不能重复绑定。";
        }

        BotUserBindingEntity entity = new BotUserBindingEntity();
        entity.setChannel(CHANNEL_QQ);
        entity.setExternalUserId(String.valueOf(qq));
        entity.setEmail(email);
        entity.setVerificationMethod(METHOD_EMAIL_CODE);
        entity.setVerifiedAt(OffsetDateTime.now());
        try {
            repository.save(entity);
        } catch (DuplicateKeyException ex) {
            return "该邮箱或 QQ 已存在绑定，请先发送 /解绑 后再试。";
        }
        try {
            legacyBindings.bind(qq, email);
        } catch (RuntimeException ex) {
            log.warn("邮箱验证已入库，但同步 Redis 绑定失败: qq={}, reason={}", qq, ex.getMessage());
        }
        try {
            redis.delete(codeKey);
        } catch (RuntimeException ex) {
            log.warn("邮箱验证码清理失败，将依赖 TTL 自动过期: qq={}, reason={}", qq, ex.getMessage());
        }
        log.info("QQ 机器人邮箱绑定成功: qq={}, email={}", qq, email);
        return "绑定成功，当前邮箱：" + email + "。";
    }

    public boolean hasPending(Long qq) {
        if (qq == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(codeKey(qq)));
        } catch (RuntimeException ex) {
            log.warn("查询邮箱验证状态失败: qq={}, reason={}", qq, ex.getMessage());
            return false;
        }
    }

    public Optional<String> verifiedEmail(Long qq) {
        if (qq == null) {
            return Optional.empty();
        }
        return repository.findByExternalUser(CHANNEL_QQ, String.valueOf(qq))
                .map(BotUserBindingEntity::getEmail);
    }

    /** 抽奖可信邮箱：验证绑定优先，其次信任 QQ 号自动匹配的 QQ 邮箱。 */
    public Optional<String> trustedEmail(Long qq) {
        Optional<String> verified = verifiedEmail(qq);
        if (verified.isPresent()) {
            return verified;
        }
        return userCache.emailByQq(qq);
    }

    /** 管理员发起解绑预览，返回二次确认提示。 */
    public String requestAdminUnbind(Long operatorQq, Long targetQq, String rawEmail) {
        if (operatorQq == null) {
            return "无法识别当前管理员 QQ。";
        }
        if (targetQq == null) {
            return adminUnbindUsage();
        }
        String email = Emails.normalize(rawEmail).orElse("");
        if (!Emails.isValid(email)) {
            return adminUnbindUsage();
        }
        if (isQqMailbox(email)) {
            return "QQ 邮箱由 QQ 号自动匹配，不支持解绑。";
        }
        Optional<BotUserBindingEntity> binding = repository.findByExternalUser(CHANNEL_QQ, String.valueOf(targetQq));
        if (binding.isEmpty() || !email.equalsIgnoreCase(binding.get().getEmail())) {
            return "未找到 用户 " + targetQq + " 与邮箱 " + email + " 的绑定关系。";
        }

        String code = generateConfirmCode();
        Map<String, String> pending = new LinkedHashMap<>();
        pending.put("targetQq", String.valueOf(targetQq));
        pending.put("email", email);
        pending.put("code", code);
        String key = adminUnbindKey(operatorQq);
        redis.opsForHash().putAll(key, pending);
        redis.expire(key, ADMIN_UNBIND_TTL);
        return "确定要解除 用户 " + targetQq + " 和 邮箱 " + email + " 的绑定吗？\n"
                + "回复：确认解绑 " + code + "\n确认码 5 分钟内有效。";
    }

    /** 管理员二次确认后执行解绑。 */
    public String confirmAdminUnbind(Long operatorQq, String rawCode) {
        if (operatorQq == null) {
            return "无法识别当前管理员 QQ。";
        }
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (!code.matches("[A-Z0-9]{6}")) {
            return adminUnbindUsage();
        }
        String key = adminUnbindKey(operatorQq);
        Map<Object, Object> pending = redis.opsForHash().entries(key);
        if (pending == null || pending.isEmpty()) {
            return "没有待确认的解绑操作，或确认码已过期。\n" + adminUnbindUsage();
        }
        String expected = String.valueOf(pending.get("code"));
        if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return "解绑确认码不正确。";
        }
        Long targetQq = parseLong(pending.get("targetQq"));
        String email = String.valueOf(pending.get("email"));
        if (targetQq == null) {
            redis.delete(key);
            return "解绑信息无效，请重新发起。\n" + adminUnbindUsage();
        }
        Optional<BotUserBindingEntity> binding = repository.findByExternalUser(CHANNEL_QQ, String.valueOf(targetQq));
        if (binding.isEmpty() || !email.equalsIgnoreCase(binding.get().getEmail())) {
            redis.delete(key);
            return "绑定关系已变化，解绑未执行。";
        }

        repository.delete(CHANNEL_QQ, String.valueOf(targetQq));
        try {
            legacyBindings.unbind(targetQq);
            redis.delete(codeKey(targetQq));
        } catch (RuntimeException ex) {
            log.warn("管理员解绑后清理缓存失败: qq={}, reason={}", targetQq, ex.getMessage());
        }
        redis.delete(key);
        log.info("管理员解除邮箱绑定: operator={}, targetQq={}, email={}", operatorQq, targetQq, email);
        return "已解除 用户 " + targetQq + " 和 邮箱 " + email + " 的绑定。";
    }

    public static String adminUnbindUsage() {
        return "解绑格式：用户 <QQ号> 邮箱 <非QQ邮箱> 解除绑定\n"
                + "指令格式：/解绑 <QQ号> <非QQ邮箱>";
    }

    private static boolean isQqMailbox(String email) {
        return email != null && email.toLowerCase(java.util.Locale.ROOT).endsWith("@qq.com");
    }

    private static Long parseLong(Object value) {
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void releaseQuota(String qqHourlyKey, String emailHourlyKey, String cooldownKey) {
        redis.opsForValue().decrement(qqHourlyKey);
        redis.opsForValue().decrement(emailHourlyKey);
        redis.delete(cooldownKey);
    }

    private long counter(String key) {
        String value = redis.opsForValue().get(key);
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Long incrementWithTtl(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, ttl);
        }
        return count;
    }

    private static int parseAttempts(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String codeKey(Long qq) {
        return CODE_KEY_PREFIX + qq;
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String generateConfirmCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(CONFIRM_CHARS.charAt(RANDOM.nextInt(CONFIRM_CHARS.length())));
        }
        return code.toString();
    }

    private static String adminUnbindKey(Long operatorQq) {
        return ADMIN_UNBIND_KEY_PREFIX + operatorQq;
    }

    static String verificationEmailHtml(String email, String code) {
        String safeEmail = MailFormat.escape(email);
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body { margin: 0; padding: 24px; background: #f4f4f5; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #18181b; }
                    .container { max-width: 640px; margin: 0 auto; background: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 8px 30px rgba(15, 23, 42, 0.10); }
                    .header { background: #0ea5e9; color: #ffffff; padding: 28px 32px; }
                    .header h1 { margin: 0; font-size: 24px; line-height: 1.25; }
                    .content { padding: 32px; font-size: 15px; line-height: 1.7; }
                    .code { margin: 24px 0; padding: 18px 20px; border-radius: 8px; background: #f0f9ff; text-align: center; font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #0369a1; }
                    .muted { color: #71717a; font-size: 13px; }
                    .footer { padding: 18px 32px; background: #fafafa; color: #a1a1aa; font-size: 12px; }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header"><h1>邮箱绑定验证</h1></div>
                    <div class="content">
                      <p>%s，您好：</p>
                      <p>您正在绑定 Monitor QQ 机器人邮箱，请输入以下验证码完成验证。</p>
                      <div class="code">%s</div>
                      <p>验证码将在 <strong>5 分钟</strong>后失效。</p>
                      <p class="muted">如果不是您本人操作，请忽略此邮件。</p>
                    </div>
                    <div class="footer">This email was sent by Monitor. Please do not reply directly.</div>
                  </div>
                </body>
                </html>
                """.formatted(safeEmail, code);
    }
}
