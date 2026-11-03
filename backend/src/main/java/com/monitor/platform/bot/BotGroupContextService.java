package com.monitor.platform.bot;

import com.monitor.platform.bot.report.EmailMasker;
import com.monitor.platform.common.util.SecretMasker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 群聊级共享上下文：滚动摘要 + 待摘要对话。
 *
 * <p>contextId 与群聊会话记忆作用域一致，因此同一群、同一人设和权限层的成员共享上下文，
 * 不同权限层之间不会串数据。摘要与待压缩对话都放在 Redis，应用重启或多实例部署时仍能延续。</p>
 */
@Service
public class BotGroupContextService {

    private static final Logger log = LoggerFactory.getLogger(BotGroupContextService.class);

    private static final String SUMMARY_PREFIX = "monitor:bot:group:summary:";
    private static final String PENDING_PREFIX = "monitor:bot:group:pending:";
    private static final Duration SUMMARY_TTL = Duration.ofDays(30);
    private static final Duration PENDING_TTL = Duration.ofDays(7);
    private static final Duration RETRY_COOLDOWN = Duration.ofMinutes(1);
    private static final int SUMMARY_TRIGGER_TURNS = 10;
    private static final int PENDING_MAX_TURNS = 40;
    private static final int MAX_SUMMARY_LENGTH = 4000;
    private static final int MAX_USER_LENGTH = 600;
    private static final int MAX_ASSISTANT_LENGTH = 900;

    private final StringRedisTemplate redis;
    private final BotChatClientFactory chatClients;
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> nextAttemptAt = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "qq-group-summary");
        thread.setDaemon(true);
        return thread;
    });

    public BotGroupContextService(StringRedisTemplate redis, BotChatClientFactory chatClients) {
        this.redis = redis;
        this.chatClients = chatClients;
    }

    /** 读取群聊当前摘要；Redis 异常时降级为空摘要。 */
    public String summary(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return "";
        }
        try {
            String summary = redis.opsForValue().get(SUMMARY_PREFIX + contextId);
            return summary == null ? "" : summary;
        } catch (RuntimeException ex) {
            log.debug("读取群聊摘要失败: context={}, reason={}", contextId, SecretMasker.safeMessage(ex));
            return "";
        }
    }

    /**
     * 记录一轮成功对话，达到阈值后在后台生成滚动摘要。
     */
    public void recordTurn(String contextId, String speaker, String userText, String assistantText) {
        if (contextId == null || contextId.isBlank() || !chatClients.available()) {
            return;
        }
        String pendingKey = PENDING_PREFIX + contextId;
        String line = speaker + "：\n" + clip(EmailMasker.maskEmails(userText), MAX_USER_LENGTH)
                + "\n机器人：\n" + clip(EmailMasker.maskEmails(assistantText), MAX_ASSISTANT_LENGTH);
        try {
            redis.opsForList().rightPush(pendingKey, line);
            redis.expire(pendingKey, PENDING_TTL);
            redis.opsForList().trim(pendingKey, -PENDING_MAX_TURNS, -1);
            Long size = redis.opsForList().size(pendingKey);
            if (size != null && size >= SUMMARY_TRIGGER_TURNS) {
                scheduleSummary(contextId);
            }
        } catch (RuntimeException ex) {
            log.debug("记录群聊摘要待处理对话失败: context={}, reason={}",
                    contextId, SecretMasker.safeMessage(ex));
        }
    }

    private void scheduleSummary(String contextId) {
        long now = System.currentTimeMillis();
        long retryAt = nextAttemptAt.getOrDefault(contextId, 0L);
        if (now < retryAt || !summarizing.add(contextId)) {
            return;
        }
        executor.submit(() -> {
            try {
                summarize(contextId);
            } finally {
                summarizing.remove(contextId);
            }
        });
    }

    private void summarize(String contextId) {
        String pendingKey = PENDING_PREFIX + contextId;
        String summaryKey = SUMMARY_PREFIX + contextId;
        List<String> pending;
        try {
            pending = redis.opsForList().range(pendingKey, 0, -1);
        } catch (RuntimeException ex) {
            log.debug("读取群聊待摘要对话失败: context={}, reason={}",
                    contextId, SecretMasker.safeMessage(ex));
            return;
        }
        if (pending == null || pending.size() < SUMMARY_TRIGGER_TURNS) {
            return;
        }

        List<String> batch = pending.subList(0, Math.min(pending.size(), SUMMARY_TRIGGER_TURNS));
        String previous = summary(contextId);
        String prompt = "旧摘要：\n" + (previous.isBlank() ? "（暂无）" : previous)
                + "\n\n新增对话：\n" + String.join("\n\n", batch);
        try {
            String updated = callSummaryModel(prompt);
            if (updated == null || updated.isBlank()) {
                throw new IllegalStateException("摘要模型返回空内容");
            }
            redis.opsForValue().set(summaryKey, clip(updated.strip(), MAX_SUMMARY_LENGTH), SUMMARY_TTL);
            redis.opsForList().trim(pendingKey, batch.size(), -1);
            redis.expire(pendingKey, PENDING_TTL);
            nextAttemptAt.remove(contextId);
            log.info("群聊摘要已更新: context={}, mergedTurns={}, summaryLength={}",
                    contextId, batch.size(), updated.length());
        } catch (RuntimeException ex) {
            nextAttemptAt.put(contextId, System.currentTimeMillis() + RETRY_COOLDOWN.toMillis());
            log.warn("群聊摘要生成失败: context={}, reason={}",
                    contextId, SecretMasker.safeMessage(ex));
        }
    }

    private String callSummaryModel(String prompt) {
        ChatClient client = chatClients.getSummaryClient();
        if (client == null) {
            client = chatClients.getFallbackSummaryClient();
        }
        if (client == null) {
            throw new IllegalStateException("未配置大模型");
        }
        try {
            return client.prompt().user(prompt).call().content();
        } catch (RuntimeException primaryError) {
            if (!chatClients.shouldFallback(primaryError)) {
                throw primaryError;
            }
            ChatClient fallback = chatClients.getFallbackSummaryClient();
            if (fallback == null) {
                throw primaryError;
            }
            try {
                return fallback.prompt().user(prompt).call().content();
            } catch (RuntimeException fallbackError) {
                fallbackError.addSuppressed(primaryError);
                throw fallbackError;
            }
        }
    }

    private static String clip(String text, int maxLength) {
        String value = text == null ? "" : text.strip();
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
