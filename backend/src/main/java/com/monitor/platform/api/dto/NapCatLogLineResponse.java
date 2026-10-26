package com.monitor.platform.api.dto;

/**
 * 一行 NapCat 日志。
 *
 * @param time    采集时间（北京时间）
 * @param level   日志级别
 * @param message 日志正文（已移除 ANSI 颜色码）
 */
public record NapCatLogLineResponse(String time, String level, String message) {
}