package com.monitor.platform.api.dto;

import com.monitor.platform.bot.BotPersonaEntity;

import java.time.OffsetDateTime;

/** 页面展示的机器人人设。 */
public record BotPersonaResponse(
        Long id,
        String name,
        String description,
        String characterPrompt,
        String stylePrompt,
        boolean enabled,
        boolean defaultPersona,
        int version,
        int boundGroupCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BotPersonaResponse from(BotPersonaEntity entity) {
        return new BotPersonaResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCharacterPrompt(),
                entity.getStylePrompt(),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getDefaultPersona()),
                entity.getVersion() == null ? 1 : entity.getVersion(),
                entity.getBoundGroupCount() == null ? 0 : entity.getBoundGroupCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
