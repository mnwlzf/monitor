package com.monitor.platform.api.dto;

import com.monitor.platform.bot.BotForbiddenWordEntity;

import java.time.OffsetDateTime;

/** 页面展示的一条违禁词。 */
public record BotForbiddenWordResponse(Long id, String word, OffsetDateTime createdAt) {

    public static BotForbiddenWordResponse from(BotForbiddenWordEntity entity) {
        return new BotForbiddenWordResponse(entity.getId(), entity.getWord(), entity.getCreatedAt());
    }
}
