package com.monitor.platform.bot;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 抽奖记录 Mapper。 */
public interface BotLotteryDrawMapper {

    BotLotteryDrawEntity selectByUserAndDate(@Param("channel") String channel,
                                             @Param("externalUserId") String externalUserId,
                                             @Param("drawDate") LocalDate drawDate);

    List<BotLotteryDrawEntity> selectRecentByUser(@Param("channel") String channel,
                                                  @Param("externalUserId") String externalUserId,
                                                  @Param("limit") int limit);

    List<BotLotteryDrawEntity> selectRecent(@Param("limit") int limit);

    int insertDraw(BotLotteryDrawEntity entity);

    int markCredited(@Param("id") Long id, @Param("creditedAt") OffsetDateTime creditedAt);

    int markFailed(@Param("id") Long id, @Param("failureReason") String failureReason);

    int markNoPrize(@Param("id") Long id);

    BigDecimal sumReservedBudget(@Param("drawDate") LocalDate drawDate,
                                 @Param("pendingSince") OffsetDateTime pendingSince,
                                 @Param("excludeId") Long excludeId);
}
