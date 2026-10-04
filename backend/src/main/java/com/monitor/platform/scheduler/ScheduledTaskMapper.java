package com.monitor.platform.scheduler;

import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 页面可配置定时任务 Mapper。
 */
public interface ScheduledTaskMapper {

    int insertTask(ScheduledTaskEntity entity);

    int updateTask(ScheduledTaskEntity entity);

    int deleteTask(@Param("id") Long id);

    ScheduledTaskEntity selectTaskById(@Param("id") Long id);

    List<ScheduledTaskEntity> selectAllTasks();

    List<ScheduledTaskEntity> selectEnabledTasks();

    int updateRunStatus(@Param("id") Long id,
                        @Param("lastRunAt") OffsetDateTime lastRunAt,
                        @Param("lastRunStatus") String lastRunStatus,
                        @Param("lastRunMessage") String lastRunMessage);
}

