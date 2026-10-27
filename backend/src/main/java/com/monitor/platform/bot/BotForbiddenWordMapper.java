package com.monitor.platform.bot;

import java.util.List;

/** 机器人违禁词 Mapper。 */
public interface BotForbiddenWordMapper {

    List<BotForbiddenWordEntity> selectAll();

    BotForbiddenWordEntity selectByWord(String word);

    int insertWord(BotForbiddenWordEntity entity);

    int deleteById(Long id);
}
