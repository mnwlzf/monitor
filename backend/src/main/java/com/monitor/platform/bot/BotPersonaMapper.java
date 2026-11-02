package com.monitor.platform.bot;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 机器人人设 Mapper。 */
public interface BotPersonaMapper {

    List<BotPersonaEntity> findAll();

    BotPersonaEntity findById(@Param("id") Long id);

    BotPersonaEntity findDefault();

    int insert(BotPersonaEntity entity);

    int update(BotPersonaEntity entity);

    int delete(@Param("id") Long id);

    int clearDefault();

    int setDefault(@Param("id") Long id);

    int countGroupBindings(@Param("id") Long id);
}
