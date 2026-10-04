package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.CreateScheduledTaskRequest;
import com.monitor.platform.api.dto.ScheduledTaskHandlerResponse;
import com.monitor.platform.api.dto.ScheduledTaskResponse;
import com.monitor.platform.api.dto.UpdateScheduledTaskRequest;
import com.monitor.platform.api.service.ScheduledTaskAdminService;
import com.monitor.platform.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 页面可配置定时任务接口。
 */
@RestController
@RequestMapping("/api/v1/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskAdminService scheduledTaskAdminService;

    public ScheduledTaskController(ScheduledTaskAdminService scheduledTaskAdminService) {
        this.scheduledTaskAdminService = scheduledTaskAdminService;
    }

    @GetMapping
    public ApiResponse<List<ScheduledTaskResponse>> listTasks() {
        return ApiResponse.of(scheduledTaskAdminService.listTasks(), null);
    }

    @GetMapping("/handlers")
    public ApiResponse<List<ScheduledTaskHandlerResponse>> listHandlers() {
        return ApiResponse.of(scheduledTaskAdminService.listHandlers(), null);
    }

    @PostMapping
    public ApiResponse<ScheduledTaskResponse> createTask(@Valid @RequestBody CreateScheduledTaskRequest request) {
        return ApiResponse.of(scheduledTaskAdminService.create(request), null);
    }

    @PutMapping("/{taskId}")
    public ApiResponse<ScheduledTaskResponse> updateTask(@PathVariable Long taskId,
                                                         @Valid @RequestBody UpdateScheduledTaskRequest request) {
        return ApiResponse.of(scheduledTaskAdminService.update(taskId, request), null);
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable Long taskId) {
        scheduledTaskAdminService.delete(taskId);
        return ApiResponse.of(null, null);
    }

    @PostMapping("/{taskId}/trigger")
    public ApiResponse<Void> triggerTask(@PathVariable Long taskId) {
        scheduledTaskAdminService.trigger(taskId);
        return ApiResponse.of(null, null);
    }
}
