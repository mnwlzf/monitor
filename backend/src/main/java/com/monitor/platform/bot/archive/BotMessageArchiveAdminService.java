package com.monitor.platform.bot.archive;

import com.monitor.platform.api.dto.BotMessageArchiveStats;
import com.monitor.platform.api.dto.BotMessageResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 消息存档的页面服务：列表查询与概况。
 */
@Service
public class BotMessageArchiveAdminService {

    private final BotMessageArchiveProperties properties;
    private final BotMessageArchiveService archiveService;

    public BotMessageArchiveAdminService(BotMessageArchiveProperties properties,
                                         BotMessageArchiveService archiveService) {
        this.properties = properties;
        this.archiveService = archiveService;
    }

    /** 按条件查询存档，时间倒序。 */
    public List<BotMessageResponse> search(String keyword, Long userId, Long groupId,
                                           OffsetDateTime from, OffsetDateTime to, Integer limit) {
        return archiveService.search(keyword, userId, groupId, from, to, limit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 存档概况。 */
    public BotMessageArchiveStats stats() {
        return new BotMessageArchiveStats(
                archiveService.enabled(),
                archiveService.count(),
                archiveService.earliest(),
                properties.getRetentionDays());
    }

    private BotMessageResponse toResponse(BotMessageArchiveEntity entity) {
        return new BotMessageResponse(
                entity.getId(),
                entity.getCreatedAt(),
                entity.getDirection(),
                entity.getMessageType(),
                entity.getGroupId(),
                entity.getUserId(),
                entity.getSenderEmail(),
                entity.getSenderRole(),
                entity.getPersona(),
                entity.getContent(),
                entity.getContentKind(),
                entity.getCorrelationId(),
                entity.getMessageId());
    }
}