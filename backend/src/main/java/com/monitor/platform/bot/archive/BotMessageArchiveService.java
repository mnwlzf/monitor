package com.monitor.platform.bot.archive;

import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.onebot.OneBotEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 机器人消息存档。
 *
 * <p><strong>全异步</strong>：调用方只生成一个关联 ID、把写入丢进队列就返回，
 * 不等待数据库 —— 存档绝不能拖慢机器人回复。</p>
 *
 * <p>关联 ID 由应用生成（不是数据库自增），所以「用户消息」与「机器人回复」
 * 两条记录可以<strong>完全独立、乱序</strong>写入，不依赖写入先后。</p>
 *
 * <p>写入是旁路：队列满或写失败只记日志，绝不抛出。</p>
 */
@Service
public class BotMessageArchiveService {

    private static final Logger log = LoggerFactory.getLogger(BotMessageArchiveService.class);

    public static final String DIRECTION_IN = "IN";
    public static final String DIRECTION_OUT = "OUT";
    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_COMMAND = "COMMAND";
    public static final String KIND_IMAGE = "IMAGE";

    /** 待写队列容量。正常消息量下远远用不满；真满了说明数据库出了问题。 */
    private static final int WRITE_QUEUE_CAPACITY = 2000;

    private final BotMessageArchiveProperties properties;
    private final BotMessageArchiveRepository repository;

    /**
     * 单线程写队列。
     *
     * <p>用单线程而不是多线程：写入天然按发生顺序落库，查询时读起来更顺；
     * 而且存档是低频操作，一个线程足够。</p>
     */
    private final ExecutorService writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "bot-archive");
                thread.setDaemon(true);
                return thread;
            },
            new LoggingDiscardOldestPolicy());

    public BotMessageArchiveService(BotMessageArchiveProperties properties,
                                    BotMessageArchiveRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * 记录一条用户消息。**立即返回**，不等待数据库。
     *
     * @return 关联 ID（用于把机器人回复和这条消息配对）；未启用时返回 {@code null}
     */
    public String recordInbound(OneBotEvent event, String content, String contentKind, BotIdentity identity) {
        if (!properties.isEnabled() || event == null || event.userId() == null) {
            return null;
        }
        String correlationId = newCorrelationId();
        submit(build(event, DIRECTION_IN, content, contentKind, identity, null, correlationId));
        return correlationId;
    }

    /** 记录一条机器人回复。同样**立即返回**。 */
    public void recordOutbound(OneBotEvent event, String content, String contentKind,
                               BotIdentity identity, String persona, String correlationId) {
        if (!properties.isEnabled() || event == null || event.userId() == null) {
            return;
        }
        submit(build(event, DIRECTION_OUT, content, contentKind, identity, persona,
                correlationId == null ? newCorrelationId() : correlationId));
    }

    /** 页面查询：按关键字 / QQ / 群 / 时间范围过滤，时间倒序。 */
    public List<BotMessageArchiveEntity> search(String keyword, Long userId, Long groupId,
                                                OffsetDateTime from, OffsetDateTime to, Integer limit) {
        int size = limit == null || limit <= 0 ? 200 : Math.min(limit, properties.getMaxPageSize());
        return repository.search(keyword, userId, groupId, from, to, size);
    }

    public long count() {
        return repository.count();
    }

    public OffsetDateTime earliest() {
        return repository.earliestCreatedAt();
    }

    /**
     * 按保留天数清理旧存档。
     *
     * @return 删除条数；未启用或保留天数非正时返回 0
     */
    public int cleanup() {
        if (!properties.isEnabled() || properties.getRetentionDays() <= 0) {
            return 0;
        }
        OffsetDateTime before = OffsetDateTime.now().minusDays(properties.getRetentionDays());
        int deleted = repository.deleteBefore(before);
        if (deleted > 0) {
            log.info("机器人消息存档清理完成: 删除 {} 条（保留 {} 天）", deleted, properties.getRetentionDays());
        }
        return deleted;
    }

    private void submit(BotMessageArchiveEntity entity) {
        try {
            writer.execute(() -> {
                try {
                    repository.insert(entity);
                } catch (Exception ex) {
                    log.warn("机器人消息存档写入失败: direction={}, user={}, reason={}",
                            entity.getDirection(), entity.getUserId(), ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("机器人消息存档队列已关闭，丢弃本条: direction={}", entity.getDirection());
        }
    }

    private BotMessageArchiveEntity build(OneBotEvent event, String direction, String content,
                                          String contentKind, BotIdentity identity,
                                          String persona, String correlationId) {
        BotMessageArchiveEntity entity = new BotMessageArchiveEntity();
        entity.setDirection(direction);
        entity.setMessageType(event.messageType() == null ? "unknown" : event.messageType());
        entity.setGroupId(event.groupId());
        entity.setUserId(event.userId());
        entity.setSenderEmail(identity == null ? null : identity.email());
        entity.setSenderRole(role(identity));
        entity.setPersona(persona);
        entity.setContent(truncate(content));
        entity.setContentKind(contentKind);
        entity.setCorrelationId(correlationId);
        entity.setMessageId(event.messageId());
        return entity;
    }

    private static String newCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String role(BotIdentity identity) {
        if (identity == null || !identity.platformUser()) {
            return "GUEST";
        }
        return identity.admin() ? "ADMIN" : "USER";
    }

    private String truncate(String content) {
        String value = content == null ? "" : content;
        int max = Math.max(100, properties.getMaxContentLength());
        return value.length() <= max ? value : value.substring(0, max) + "…（已截断）";
    }

    /** 关停时尽量把队列里的存档写完，但最多等 5 秒，不拖慢容器停止。 */
    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    /**
     * 队列满时丢最旧的一条。
     *
     * <p>存档是旁路：宁可少存几条，也不能让队列无限增长或让调用方阻塞。
     * 丢最旧而不是丢最新 —— 最近的消息对查证更有价值。</p>
     */
    private static final class LoggingDiscardOldestPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            if (executor.getQueue().poll() != null) {
                log.warn("消息存档写入积压，丢弃最旧的一条待写记录");
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // 与关闭竞态，放弃这条存档即可
            }
        }
    }
}