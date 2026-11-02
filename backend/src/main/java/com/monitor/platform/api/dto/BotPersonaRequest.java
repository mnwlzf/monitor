package com.monitor.platform.api.dto;

/**
 * 新增或更新机器人人设。
 *
 * <p>人设只影响自然语言表达，不承载任何权限或工具开关。</p>
 */
public record BotPersonaRequest(
        String name,
        String description,
        String characterPrompt,
        String stylePrompt,
        Boolean enabled
) {
}
