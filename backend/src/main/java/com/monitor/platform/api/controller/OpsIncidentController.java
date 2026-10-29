package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.ExecuteOpsPlanRequest;
import com.monitor.platform.api.dto.OpsActionExecutionResponse;
import com.monitor.platform.api.dto.OpsActionPlanResponse;
import com.monitor.platform.api.dto.OpsIncidentEventResponse;
import com.monitor.platform.api.dto.OpsIncidentResponse;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.common.response.ApiResponse;
import com.monitor.platform.ops.OpsIncidentService;
import com.monitor.platform.ops.OpsIncidentService.ExecutionResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 智能运维事故中心与自愈计划接口。 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsIncidentController {

    private final OpsIncidentService incidentService;

    public OpsIncidentController(OpsIncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping("/incidents")
    public ApiResponse<List<OpsIncidentResponse>> listIncidents(
            @RequestParam(value = "status", required = false, defaultValue = "OPEN") String status,
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        return ApiResponse.of(incidentService.listIncidents(status, size).stream()
                .map(OpsIncidentResponse::from).toList(), null);
    }

    @GetMapping("/incidents/{id}")
    public ApiResponse<OpsIncidentResponse> getIncident(@PathVariable Long id) {
        return ApiResponse.of(incidentService.findIncident(id)
                .map(OpsIncidentResponse::from)
                .orElseThrow(() -> BusinessException.of("事故不存在: " + id)), null);
    }

    @GetMapping("/incidents/{id}/events")
    public ApiResponse<List<OpsIncidentEventResponse>> incidentEvents(
            @PathVariable Long id,
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        return ApiResponse.of(incidentService.incidentEvents(id, size).stream()
                .map(OpsIncidentEventResponse::from).toList(), null);
    }

    @PostMapping("/incidents/refresh")
    public ApiResponse<Integer> refreshIncidents() {
        return ApiResponse.of(incidentService.refreshIncidents(), null);
    }

    @PostMapping("/incidents/{id}/plans")
    public ApiResponse<OpsActionPlanResponse> createPlan(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.of(OpsActionPlanResponse.from(
                incidentService.createPlan(id, actor(authentication))), null);
    }

    @GetMapping("/plans")
    public ApiResponse<List<OpsActionPlanResponse>> listPlans(
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        return ApiResponse.of(incidentService.recentPlans(size).stream()
                .map(OpsActionPlanResponse::from).toList(), null);
    }

    @GetMapping("/plans/{id}")
    public ApiResponse<OpsActionPlanResponse> getPlan(@PathVariable Long id) {
        return ApiResponse.of(incidentService.findPlan(id)
                .map(OpsActionPlanResponse::from)
                .orElseThrow(() -> BusinessException.of("自愈计划不存在: " + id)), null);
    }

    @PostMapping("/plans/{id}/execute")
    public ApiResponse<OpsActionExecutionResponse> executePlan(
            @PathVariable Long id,
            @RequestBody ExecuteOpsPlanRequest request,
            Authentication authentication) {
        ExecutionResult result = incidentService.executePlan(
                id, request == null ? null : request.confirmationCode(), actor(authentication));
        return ApiResponse.of(new OpsActionExecutionResponse(
                result.success(), result.message(), OpsActionPlanResponse.from(result.plan())), null);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "system" : authentication.getName();
    }
}