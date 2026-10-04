package com.monitor.platform.scheduler;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 页面可配置定时任务持久化仓储。
 */
@Repository
public class ScheduledTaskRepository {

    private final ScheduledTaskMapper scheduledTaskMapper;

    public ScheduledTaskRepository(ScheduledTaskMapper scheduledTaskMapper) {
        this.scheduledTaskMapper = scheduledTaskMapper;
    }

    /** 新增或更新任务，并维护时间戳。 */
    public ScheduledTaskEntity save(ScheduledTaskEntity entity) {
        OffsetDateTime now = OffsetDateTime.now();
        entity.setUpdatedAt(now);
        if (entity.getId() == null) {
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
            scheduledTaskMapper.insertTask(entity);
        } else {
            scheduledTaskMapper.updateTask(entity);
        }
        return entity;
    }

    /** 按主键查询任务。 */
    public Optional<ScheduledTaskEntity> findById(Long id) {
        return Optional.ofNullable(scheduledTaskMapper.selectTaskById(id));
    }

    /** 查询全部任务。 */
    public List<ScheduledTaskEntity> findAll() {
        return scheduledTaskMapper.selectAllTasks();
    }

    /** 查询所有启用任务。 */
    public List<ScheduledTaskEntity> findEnabled() {
        return scheduledTaskMapper.selectEnabledTasks();
    }

    /** 硬删除任务。 */
    public void deleteById(Long id) {
        scheduledTaskMapper.deleteTask(id);
    }

    /** 更新任务最近一次执行状态。 */
    public void updateRunStatus(Long id, OffsetDateTime lastRunAt, String lastRunStatus, String lastRunMessage) {
        scheduledTaskMapper.updateRunStatus(id, lastRunAt, lastRunStatus, lastRunMessage);
    }
}

