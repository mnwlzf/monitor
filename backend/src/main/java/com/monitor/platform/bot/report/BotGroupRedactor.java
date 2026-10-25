package com.monitor.platform.bot.report;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.mail.MailFormat;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群聊输出脱敏：功能照常执行，但账号名称、上游平台名称和平台地址不直接展示。
 *
 * <p>所有群聊/群临时会话的最终报表在发送前经过这里。平台按 ID 排序映射为
 * 「上游平台1/2/…」，账号映射为「上游账号1/2/…」；同一份数据在多轮消息里标签稳定。
 * 邮箱统一替换为「[邮箱已隐藏]」。</p>
 */
@Service
public class BotGroupRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9_])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9_])");
    private static final long CACHE_MILLIS = 30_000L;

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private volatile CachedReplacements cache;

    public BotGroupRedactor(PlatformRepository platformRepository, AccountRepository accountRepository) {
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
    }

    /** 对群聊最终输出做脱敏；非群聊上下文由调用方决定不调用。 */
    public BotReport redact(BotReport report) {
        if (report == null) {
            return null;
        }
        List<Replacement> replacements = replacements();
        if (replacements.isEmpty()) {
            return report;
        }
        return report.mapText(text -> redactText(text, replacements));
    }

    private List<Replacement> replacements() {
        CachedReplacements cached = cache;
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.replacements();
        }
        synchronized (this) {
            cached = cache;
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.replacements();
            }
            List<Replacement> loaded = loadReplacements();
            cache = new CachedReplacements(Instant.now().plusMillis(CACHE_MILLIS), loaded);
            return loaded;
        }
    }

    private List<Replacement> loadReplacements() {
        Map<String, String> values = new LinkedHashMap<>();
        List<PlatformEntity> platforms = platformRepository.findAll().stream()
                .sorted(Comparator.comparing(PlatformEntity::getId, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        int platformIndex = 1;
        for (PlatformEntity platform : platforms) {
            String label = "上游平台" + platformIndex++;
            add(values, platform.getPlatformName(), label);
            add(values, platform.getUrl(), label);
            add(values, hostOf(platform.getUrl()), label);
        }

        List<AccountEntity> accounts = new ArrayList<>();
        for (PlatformEntity platform : platforms) {
            accounts.addAll(accountRepository.findByPlatformId(platform.getId()));
        }
        accounts.sort(Comparator.comparing(AccountEntity::getId, Comparator.nullsLast(Integer::compareTo)));

        int accountIndex = 1;
        for (AccountEntity account : accounts) {
            String label = "上游账号" + accountIndex++;
            add(values, MailFormat.displayName(account), label);
            add(values, account.getDisplayName(), label);
            add(values, account.getUsername(), label);
            add(values, account.getEmail(), label);
            add(values, account.getUrl(), label);
            add(values, account.getId() == null ? null : "账号 " + account.getId(), label);
            add(values, account.getId() == null ? null : "账号" + account.getId(), label);
        }

        return values.entrySet().stream()
                .map(entry -> new Replacement(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((Replacement item) -> item.from().length()).reversed())
                .toList();
    }

    private static void add(Map<String, String> values, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        values.putIfAbsent(raw.trim(), label);
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String redactText(String text, List<Replacement> replacements) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Replacement replacement : replacements) {
            result = replacement.pattern().matcher(result)
                    .replaceAll(Matcher.quoteReplacement(replacement.to()));
        }
        return EMAIL.matcher(result).replaceAll("[邮箱已隐藏]");
    }

    private record Replacement(String from, Pattern pattern, String to) {
        Replacement(String from, String to) {
            this(from, patternOf(from), to);
        }

        private static Pattern patternOf(String value) {
            String quoted = Pattern.quote(value);
            String body = value.matches("[A-Za-z0-9_.-]+")
                    ? "(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])"
                    : quoted;
            return Pattern.compile(body, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
    }

    private record CachedReplacements(Instant expiresAt, List<Replacement> replacements) {
    }
}
