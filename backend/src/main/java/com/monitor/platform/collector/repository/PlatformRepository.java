package com.monitor.platform.collector.repository;

import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.collector.repository.mapper.PlatformMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 平台实例持久化仓储。
 */
@Repository
public class PlatformRepository {

    private final PlatformMapper platformMapper;

    public PlatformRepository(PlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    /**
     * 新增或更新平台实例，并维护更新时间。
     */
    public PlatformEntity save(PlatformEntity entity) {
        OffsetDateTime now = OffsetDateTime.now();
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            platformMapper.insertPlatform(entity);
        } else {
            platformMapper.updatePlatform(entity);
        }
        return entity;
    }

    /**
     * 按主键查询未删除平台。
     */
    public Optional<PlatformEntity> findById(Integer id) {
        return Optional.ofNullable(platformMapper.selectPlatformById(id));
    }

    /**
     * 按平台名称查询平台，用于唯一性校验。
     */
    public Optional<PlatformEntity> findByName(String platformName) {
        return Optional.ofNullable(platformMapper.selectPlatformByName(platformName));
    }

    /**
     * 查询所有启用平台，供采集调度使用。
     */
    public List<PlatformEntity> findEnabled() {
        return platformMapper.selectEnabledPlatforms();
    }

    /**
     * 查询所有未删除平台（含已停用），供管理页面展示。
     */
    public List<PlatformEntity> findAll() {
        return platformMapper.selectAllPlatforms();
    }

    /**
     * 软删除平台，保留历史账号和快照关联。
     */
    public void softDelete(Integer id) {
        platformMapper.softDeletePlatform(id, OffsetDateTime.now());
    }
}