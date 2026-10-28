package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 机器人已验证邮箱绑定仓储。 */
@Repository
public class BotUserBindingRepository {

    private final BotUserBindingMapper mapper;

    public BotUserBindingRepository(BotUserBindingMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<BotUserBindingEntity> findByExternalUser(String channel, String externalUserId) {
        return Optional.ofNullable(mapper.selectByExternalUser(channel, externalUserId));
    }

    public Optional<BotUserBindingEntity> findByEmail(String channel, String email) {
        return Optional.ofNullable(mapper.selectByEmail(channel, email));
    }

    public void save(BotUserBindingEntity entity) {
        mapper.upsertBinding(entity);
    }

    public boolean delete(String channel, String externalUserId) {
        return mapper.deleteByExternalUser(channel, externalUserId) > 0;
    }
}
