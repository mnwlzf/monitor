package com.monitor.platform.bot.archive;

import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 机器人消息存档 Mapper。
 */
public interface BotMessageArchiveMapper {

    int insert(BotMessageArchiveEntity entity);

    /**
     * 按条件查询，时间倒序。
     *
     * @param keyword 内容模糊匹配（可为空）
     * @param userId  指定 QQ（可为空）
     * @param groupId 指定群（可为空）
     * @param from    起始时间（可为空）
     * @param to      结束时间（可为空）
     * @param limit   返回条数上限
     */
    List<BotMessageArchiveEntity> search(@Param("keyword") String keyword,
                                         @Param("userId") Long userId,
                                         @Param("groupId") Long groupId,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to,
                                         @Param("limit") int limit);

    /** 删除指定时间之前的记录，返回删除条数。 */
    int deleteBefore(@Param("before") OffsetDateTime before);

    /** 最早一条记录的时间，用于页面提示存档起点。 */
    OffsetDateTime earliestCreatedAt();

    long count();
}