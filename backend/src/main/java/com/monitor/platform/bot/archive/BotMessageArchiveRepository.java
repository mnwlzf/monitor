package com.monitor.platform.bot.archive;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 机器人消息存档仓储。
 */
@Repository
public class BotMessageArchiveRepository {

    private final BotMessageArchiveMapper mapper;

    public BotMessageArchiveRepository(BotMessageArchiveMapper mapper) {
        this.mapper = mapper;
    }

    /** 写入一条存档，主键回填到实体。 */
    public void insert(BotMessageArchiveEntity entity) {
        mapper.insert(entity);
    }

    public List<BotMessageArchiveEntity> search(String keyword, Long userId, Long groupId,
                                                OffsetDateTime from, OffsetDateTime to, int limit) {
        return mapper.search(keyword, userId, groupId, from, to, limit);
    }

    /** 清理指定时间之前的存档，返回删除条数。 */
    public int deleteBefore(OffsetDateTime before) {
        return mapper.deleteBefore(before);
    }

    public OffsetDateTime earliestCreatedAt() {
        return mapper.earliestCreatedAt();
    }

    public long count() {
        return mapper.count();
    }
}