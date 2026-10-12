package com.monitor.platform.collector.application;

/**
 * 上游变更类型常量。
 *
 * <p>变更类型同时被「采集侧」（生成事件）与「记录侧」（判定级别 / 使用中）引用，
 * 集中一处避免两边各写一份字符串后逐渐漂移。取值即落库的 {@code change_type}。</p>
 */
final class UpstreamChangeTypes {

    static final String GROUP_ADDED = "GROUP_ADDED";
    static final String GROUP_REMOVED = "GROUP_REMOVED";
    static final String GROUP_UPDATED = "GROUP_UPDATED";
    static final String RATE_CHANGED = "RATE_CHANGED";
    static final String BASE_RATE_CHANGED = "BASE_RATE_CHANGED";
    static final String STATUS_CHANGED = "STATUS_CHANGED";
    static final String API_KEY_ADDED = "API_KEY_ADDED";
    static final String API_KEY_REMOVED = "API_KEY_REMOVED";
    static final String API_KEY_UPDATED = "API_KEY_UPDATED";
    static final String API_KEY_ROTATED = "API_KEY_ROTATED";

    private UpstreamChangeTypes() {
    }
}
