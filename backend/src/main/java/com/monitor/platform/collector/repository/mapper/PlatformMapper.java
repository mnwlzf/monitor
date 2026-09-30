package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台实例 Mapper。
 */
public interface PlatformMapper  {

    int insertPlatform(PlatformEntity entity);

    int updatePlatform(PlatformEntity entity);

    PlatformEntity selectPlatformById(@Param("id") Integer id);

    PlatformEntity selectPlatformByName(@Param("platformName") String platformName);

    List<PlatformEntity> selectEnabledPlatforms();
}