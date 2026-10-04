package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.CreateScheduledTaskRequest;
import com.monitor.platform.api.dto.ScheduledTaskHandlerResponse;
import com.monitor.platform.api.dto.ScheduledTaskResponse;
import com.monitor.platform.api.dto.UpdateScheduledTaskRequest;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.scheduler.DynamicScheduledTaskManager;
import com.monitor.platform.scheduler.ScheduledTaskEntity;
import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import com.monitor.platform.scheduler.ScheduledTaskRepository;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面可配置定时任务管理服务。
 */
@Service
public class ScheduledTaskAdminService {

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final DynamicScheduledTaskManager dynamicScheduledTaskManager;

    public ScheduledTaskAdminService(ScheduledTaskRepository scheduledTaskRepository,
                                     DynamicScheduledTaskManager dynamicScheduledTaskManager) {
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.dynamicScheduledTaskManager = dynamicScheduledTaskManager;
    }

    /** 查询全部任务。 */
    public List<ScheduledTaskResponse> listTasks() {
        Map<String, ScheduledTaskHandler> handlers = handlerMap();
        return scheduledTaskRepository.findAll().stream()
                .map(task -> toResponse(task, handlers.get(task.getTaskCode())))
                .toList();
    }

    /** 查询页面可选的任务处理器。 */
    public List<ScheduledTaskHandlerResponse> listHandlers() {
        return dynamicScheduledTaskManager.listHandlers().stream()
                .map(handler -> new ScheduledTaskHandlerResponse(
                        handler.code(), handler.name(), handler.description()))
                .toList();
    }

    @Transactional
    public ScheduledTaskResponse create(CreateScheduledTaskRequest request) {
        validateHandler(request.taskCode());
        validateCron(request.cronExpression());
        validateTimezone(request.timezone());

        ScheduledTaskEntity entity = new ScheduledTaskEntity();
        entity.setTaskName(request.taskName());
        entity.setTaskCode(request.taskCode());
        entity.setCronExpression(request.cronExpression());
        entity.setTimezone(normalizeTimezone(request.timezone()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDescription(request.description());
        scheduledTaskRepository.save(entity);
        dynamicScheduledTaskManager.reload();
        return toResponse(entity, handler(request.taskCode()));
    }

    @Transactional
    public ScheduledTaskResponse update(Long taskId, UpdateScheduledTaskRequest request) {
        ScheduledTaskEntity entity = scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> BusinessException.of("定时任务不存在: " + taskId));
        validateHandler(request.taskCode());
        validateCron(request.cronExpression());
        validateTimezone(request.timezone());

        entity.setTaskName(request.taskName());
        entity.setTaskCode(request.taskCode());
        entity.setCronExpression(request.cronExpression());
        entity.setTimezone(normalizeTimezone(request.timezone()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDescription(request.description());
        scheduledTaskRepository.save(entity);
        dynamicScheduledTaskManager.reload();
        return toResponse(entity, handler(request.taskCode()));
    }

    @Transactional
    public void delete(Long taskId) {
        ScheduledTaskEntity entity = scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> BusinessException.of("定时任务不存在: " + taskId));
        scheduledTaskRepository.deleteById(taskId);
        dynamicScheduledTaskManager.reload();
    }

    /** 立即触发一次任务。 */
    public void trigger(Long taskId) {
        scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> BusinessException.of("定时任务不存在: " + taskId));
        dynamicScheduledTaskManager.triggerNow(taskId);
    }

    private ScheduledTaskResponse toResponse(ScheduledTaskEntity entity, ScheduledTaskHandler handler) {
        return new ScheduledTaskResponse(
                entity.getId(),
                entity.getTaskName(),
                entity.getTaskCode(),
                handler == null ? entity.getTaskCode() : handler.name(),
                entity.getCronExpression(),
                entity.getTimezone(),
                entity.getEnabled(),
                entity.getDescription(),
                entity.getLastRunAt(),
                entity.getLastRunStatus(),
                entity.getLastRunMessage()
        );
    }

    private void validateHandler(String taskCode) {
        if (!dynamicScheduledTaskManager.containsHandler(taskCode)) {
            throw BusinessException.of("不支持的任务类型: " + taskCode);
        }
    }

    private void validateCron(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (Exception ex) {
            throw BusinessException.of("Cron 表达式不合法: " + cron);
        }
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(normalizeTimezone(timezone));
        } catch (Exception ex) {
            throw BusinessException.of("时区不合法: " + timezone);
        }
    }

    private String normalizeTimezone(String timezone) {
        return timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
    }

    private Map<String, ScheduledTaskHandler> handlerMap() {
        Map<String, ScheduledTaskHandler> map = new HashMap<>();
        for (ScheduledTaskHandler handler : dynamicScheduledTaskManager.listHandlers()) {
            map.put(handler.code(), handler);
        }
        return map;
    }

    private ScheduledTaskHandler handler(String taskCode) {
        return dynamicScheduledTaskManager.listHandlers().stream()
                .filter(item -> item.code().equals(taskCode))
                .findFirst()
                .orElse(null);
    }
}
