package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台实例 Mapper。
 */
public interface PlatformMapper  {

    /** 新增平台。 */
    int insertPlatform(PlatformEntity entity);

    /** 更新平台。 */
    int updatePlatform(PlatformEntity entity);

    /** 按主键查询平台。 */
    PlatformEntity selectPlatformById(@Param("id") Integer id);

    /** 按名称查询平台。 */
    PlatformEntity selectPlatformByName(@Param("platformName") String platformName);

    /** 软删除平台。 */
    int softDeletePlatform(@Param("id") Integer id, @Param("deletedAt") java.time.OffsetDateTime deletedAt);

    /** 查询所有启用平台，供采集调度使用。 */
    List<PlatformEntity> selectEnabledPlatforms();

    /** 查询所有未删除平台（含已停用），供管理页面展示。 */
    List<PlatformEntity> selectAllPlatforms();
}