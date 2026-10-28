package com.monitor.platform.bot;

import org.apache.ibatis.annotations.Param;

/** 机器人已验证邮箱绑定 Mapper。 */
public interface BotUserBindingMapper {

    BotUserBindingEntity selectByExternalUser(@Param("channel") String channel,
                                              @Param("externalUserId") String externalUserId);

    BotUserBindingEntity selectByEmail(@Param("channel") String channel, @Param("email") String email);

    int upsertBinding(BotUserBindingEntity entity);

    int deleteByExternalUser(@Param("channel") String channel,
                             @Param("externalUserId") String externalUserId);
}
