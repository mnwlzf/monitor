package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 抽奖记录仓储。 */
@Repository
public class BotLotteryDrawRepository {

    private final BotLotteryDrawMapper mapper;

    public BotLotteryDrawRepository(BotLotteryDrawMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<BotLotteryDrawEntity> findByUserAndDate(String channel, String externalUserId, LocalDate drawDate) {
        return Optional.ofNullable(mapper.selectByUserAndDate(channel, externalUserId, drawDate));
    }

    public List<BotLotteryDrawEntity> recentByUser(String channel, String externalUserId, int limit) {
        return mapper.selectRecentByUser(channel, externalUserId, limit);
    }

    public List<BotLotteryDrawEntity> recent(int limit) {
        return mapper.selectRecent(limit);
    }

    public void insert(BotLotteryDrawEntity entity) {
        mapper.insertDraw(entity);
    }

    public void markCredited(Long id, OffsetDateTime creditedAt) {
        mapper.markCredited(id, creditedAt);
    }

    public void markFailed(Long id, String failureReason) {
        mapper.markFailed(id, failureReason);
    }

    public void markNoPrize(Long id) {
        mapper.markNoPrize(id);
    }

    public BigDecimal reservedBudget(LocalDate drawDate, OffsetDateTime pendingSince, Long excludeId) {
        BigDecimal value = mapper.sumReservedBudget(drawDate, pendingSince, excludeId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal reservedBudget(LocalDate drawDate, OffsetDateTime pendingSince) {
        return reservedBudget(drawDate, pendingSince, null);
    }
}
