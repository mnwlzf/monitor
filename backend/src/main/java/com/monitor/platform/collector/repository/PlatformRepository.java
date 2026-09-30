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

    public Optional<PlatformEntity> findById(Integer id) {
        return Optional.ofNullable(platformMapper.selectPlatformById(id));
    }

    public Optional<PlatformEntity> findByName(String platformName) {
        return Optional.ofNullable(platformMapper.selectPlatformByName(platformName));
    }

    public List<PlatformEntity> findEnabled() {
        return platformMapper.selectEnabledPlatforms();
    }
}