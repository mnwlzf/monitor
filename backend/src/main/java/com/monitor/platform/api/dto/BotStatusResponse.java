package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 机器人运行状态（「机器人状态」页面用）。
 *
 * <p>把「机器人现在到底在干什么」一次讲清楚：开关、大模型、OneBot 连接、
 * 收发计数，以及<strong>最近哪些消息没被回复、为什么</strong>。</p>
 *
 * @param enabled       机器人总开关（来自页面配置）
 * @param startedAt     进程启动时间
 * @param uptimeSeconds 已运行秒数
 * @param llm           大模型状态
 * @param onebot        OneBot / NapCat 连接状态（实时探测）
 * @param counters      本次启动以来的收发计数
 * @param activeTurns   正在处理的对话（谁、哪个群、说了什么）
 * @param recentTurns   最近完成的对话（问了什么、答了什么、耗时）
 * @param dropReasons   丢弃原因分布
 * @param recentDrops   最近被丢弃的消息（最新在前）
 * @param recentErrors  最近的处理错误（最新在前）
 * @param imageGen      生图功能状态
 */
public record BotStatusResponse(
        boolean enabled,
        OffsetDateTime startedAt,
        long uptimeSeconds,
        LlmInfo llm,
        OneBotInfo onebot,
        Counters counters,
        List<ActiveTurnView> activeTurns,
        List<TurnView> recentTurns,
        Map<String, Long> dropReasons,
        List<DropView> recentDrops,
        List<ErrorView> recentErrors,
        ImageGenInfo imageGen
) {

    /** 大模型状态。 */
    public record LlmInfo(boolean available, String model, String baseUrl) {
    }

    /** OneBot 连接状态。 */
    public record OneBotInfo(boolean configured, boolean reachable, boolean online,
                             String baseUrl, Long selfId, String nickname, String error) {
    }

    /** 收发计数。 */
    public record Counters(long inbound, long replied, long dropped, long failed,
                           OffsetDateTime lastInboundAt, OffsetDateTime lastOutboundAt) {
    }

    /** 正在处理的一轮对话。 */
    public record ActiveTurnView(OffsetDateTime at, String channel, Long groupId, Long userId, String text) {
    }

    /** 一轮已完成的对话。 */
    public record TurnView(OffsetDateTime at, String channel, Long groupId, Long userId,
                           String question, String answer, long elapsedMs) {
    }

    /** 一条被丢弃的消息。 */
    public record DropView(OffsetDateTime at, String channel, Long groupId, Long userId, String reason) {
    }

    /** 一条处理错误。 */
    public record ErrorView(OffsetDateTime at, Long userId, String message) {
    }

    /** 生图功能状态。 */
    public record ImageGenInfo(boolean usable, String model, int maxPerMinute) {
    }
}
