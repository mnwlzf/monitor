package com.monitor.platform.bot;

/**
 * 消息热路径使用的人设快照。
 *
 * <p>{@code version} 会进入 ChatClient 缓存键和会话记忆作用域，避免修改人设后沿用旧上下文。</p>
 */
public record BotPersonaSnapshot(
        long id,
        String name,
        int version,
        String characterPrompt,
        String stylePrompt
) {

    public static final BotPersonaSnapshot BUILTIN = new BotPersonaSnapshot(
            0L,
            "内置默认人设",
            0,
            "你是一名可靠、克制的平台助手，负责依据系统允许的工具和真实数据回答问题。",
            "使用简体中文，专业、友好、简洁。"
    );

    public String archiveLabel() {
        return name + "#v" + version;
    }
}
