package com.monitor.platform.bot;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 机器人运行时的内存态：计数器 + 最近事件环形缓冲。
 *
 * <p>存在的理由：机器人「为什么不回我」以前只能翻日志（而且白名单拦截原本是 debug 级，
 * 默认配置下完全无痕）。这里把关键事件记在内存里，由「机器人状态」页面直接展示。</p>
 *
 * <p><strong>刻意不落库</strong>：这些是进程级的观测数据，重启即清零，
 * 写库反而增加每条消息的写放大。要看历史记录用消息存档页。</p>
 *
 * <p>线程安全：计数器用 {@link AtomicLong}，两个环形缓冲用 {@code synchronized} 保护
 * （每条消息最多加一次锁，量级可忽略）。</p>
 */
@Component
public class BotRuntimeStatus {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一段对话内容最多留这么多字符（问与答各自截断）。 */
    private static final int MAX_TEXT = 200;

    /** 最近事件最多留这么多条，避免无限增长。 */
    private static final int MAX_RECENT = 30;

    private final Instant startedAt = Instant.now();

    private final AtomicLong inbound = new AtomicLong();
    private final AtomicLong replied = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /** 丢弃原因 -> 次数，便于一眼看出「主要是什么被拦下了」。 */
    private final Map<String, Long> dropReasons = new LinkedHashMap<>();

    private final Deque<DropEvent> recentDrops = new ArrayDeque<>();
    private final Deque<ErrorEvent> recentErrors = new ArrayDeque<>();

    /** 正在处理的一轮对话（key 是内部序号）。 */
    private final Map<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();

    /** 最近完成的对话：问了什么、答了什么、花了多久。 */
    private final Deque<Turn> recentTurns = new ArrayDeque<>();
    private final AtomicLong turnSeq = new AtomicLong();

    private volatile OffsetDateTime lastInboundAt;
    private volatile OffsetDateTime lastOutboundAt;

    /**
     * 开始一轮对话，返回用于结束它的 id。
     *
     * <p>「机器人状态」页面用它在「正在处理」里显示：正在和谁、哪个群、说的什么。</p>
     */
    public String beginTurn(BotChannel channel, Long groupId, Long userId, String text) {
        String id = "t" + turnSeq.incrementAndGet();
        activeTurns.put(id, new ActiveTurn(OffsetDateTime.now(ZONE), String.valueOf(channel),
                groupId, userId, clip(text)));
        return id;
    }

    /** 结束一轮对话，把「问了什么 / 答了什么 / 花了多久」记进最近对话。 */
    public void finishTurn(String turnId, String answer) {
        if (turnId == null) {
            return;
        }
        ActiveTurn turn = activeTurns.remove(turnId);
        if (turn == null) {
            return;
        }
        long elapsedMs = Math.max(0, Duration.between(turn.at().toInstant(), Instant.now()).toMillis());
        Turn done = new Turn(turn.at(), turn.channel(), turn.groupId(), turn.userId(),
                turn.text(), clip(answer), elapsedMs);
        synchronized (recentTurns) {
            recentTurns.addFirst(done);
            while (recentTurns.size() > MAX_RECENT) {
                recentTurns.removeLast();
            }
        }
    }

    /** 正在处理的对话（按开始时间，早的在前）。 */
    public List<ActiveTurn> activeTurns() {
        List<ActiveTurn> list = new ArrayList<>(activeTurns.values());
        list.sort(Comparator.comparing(ActiveTurn::at));
        return list;
    }

    /** 最近完成的对话，最新在前。 */
    public List<Turn> recentTurns() {
        synchronized (recentTurns) {
            return new ArrayList<>(recentTurns);
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.trim().replaceAll("\\s+", " ");
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT) + "…";
    }    /** 收到一条会被处理的消息（已通过白名单等闸门）。 */
    public void inbound() {
        inbound.incrementAndGet();
        lastInboundAt = OffsetDateTime.now(ZONE);
    }

    /** 成功发出一条回复。 */
    public void replied() {
        replied.incrementAndGet();
        lastOutboundAt = OffsetDateTime.now(ZONE);
    }

    /** 一条消息被丢弃（未回复），reason 会展示在页面上。 */
    public void dropped(BotChannel channel, Long groupId, Long userId, String reason) {
        dropped.incrementAndGet();
        String key = reason == null || reason.isBlank() ? "未知原因" : reason;
        DropEvent event = new DropEvent(OffsetDateTime.now(ZONE), String.valueOf(channel), groupId, userId, key);
        synchronized (recentDrops) {
            dropReasons.merge(key, 1L, Long::sum);
            recentDrops.addFirst(event);
            while (recentDrops.size() > MAX_RECENT) {
                recentDrops.removeLast();
            }
        }
    }

    /** 处理一条消息时出错。 */
    public void failed(Long userId, String message) {
        failed.incrementAndGet();
        String text = message == null || message.isBlank() ? "未知错误" : message;
        if (text.length() > 300) {
            text = text.substring(0, 300) + "…";
        }
        ErrorEvent event = new ErrorEvent(OffsetDateTime.now(ZONE), userId, text);
        synchronized (recentErrors) {
            recentErrors.addFirst(event);
            while (recentErrors.size() > MAX_RECENT) {
                recentErrors.removeLast();
            }
        }
    }

    /** 进程启动时刻。 */
    public Instant startedAt() {
        return startedAt;
    }

    /** 已经跑了多久（秒）。 */
    public long uptimeSeconds() {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
    }

    public long inboundCount() {
        return inbound.get();
    }

    public long repliedCount() {
        return replied.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public OffsetDateTime lastInboundAt() {
        return lastInboundAt;
    }

    public OffsetDateTime lastOutboundAt() {
        return lastOutboundAt;
    }

    /** 丢弃原因分布（按出现顺序）。 */
    public Map<String, Long> dropReasons() {
        synchronized (recentDrops) {
            return new LinkedHashMap<>(dropReasons);
        }
    }

    /** 最近的丢弃事件，最新在前。 */
    public List<DropEvent> recentDrops() {
        synchronized (recentDrops) {
            return new ArrayList<>(recentDrops);
        }
    }

    /** 最近的错误，最新在前。 */
    public List<ErrorEvent> recentErrors() {
        synchronized (recentErrors) {
            return new ArrayList<>(recentErrors);
        }
    }

    /**
     * 正在处理的一轮对话。
     *
     * @param at      收到时间（含排队等待）
     * @param channel 通道
     * @param groupId 群号（群聊为本群，临时会话为发起群，好友私聊为空）
     * @param userId  发送者 QQ
     * @param text    用户说了什么（已截断）
     */
    public record ActiveTurn(OffsetDateTime at, String channel, Long groupId, Long userId, String text) {
    }

    /**
     * 一轮已完成的对话。
     *
     * @param at        开始时间
     * @param channel   通道
     * @param groupId   群号
     * @param userId    发送者 QQ
     * @param question  用户说了什么（已截断）
     * @param answer    机器人答了什么（已截断）
     * @param elapsedMs 从收到到发出用了多久
     */
    public record Turn(OffsetDateTime at, String channel, Long groupId, Long userId,
                       String question, String answer, long elapsedMs) {
    }    /**
     * 一条被丢弃的消息。
     *
     * @param at      发生时间
     * @param channel 通道：GROUP / TEMP_SESSION / PRIVATE
     * @param groupId 群号（群聊为本群，临时会话为发起群，好友私聊为空）
     * @param userId  发送者 QQ
     * @param reason  为什么没回
     */
    public record DropEvent(OffsetDateTime at, String channel, Long groupId, Long userId, String reason) {
    }

    /**
     * 一条处理错误。
     *
     * @param at      发生时间
     * @param userId  发送者 QQ
     * @param message 错误信息（已截断）
     */
    public record ErrorEvent(OffsetDateTime at, Long userId, String message) {
    }
}
