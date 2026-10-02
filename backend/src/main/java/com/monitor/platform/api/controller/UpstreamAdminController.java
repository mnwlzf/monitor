package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountUsageDashboardResponse;
import com.monitor.platform.api.dto.CreateAccountRequest;
import com.monitor.platform.api.dto.CreatePlatformRequest;
import com.monitor.platform.api.dto.PlatformResponse;
import com.monitor.platform.api.dto.UpstreamChangeEventResponse;
import com.monitor.platform.api.dto.UpstreamGroupResponse;
import com.monitor.platform.api.dto.UpdateAccountRequest;
import com.monitor.platform.api.dto.UpdatePlatformRequest;
import com.monitor.platform.api.service.UpstreamAdminService;
import com.monitor.platform.collector.application.CollectionService;
import com.monitor.platform.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 上游平台和账号管理接口。
 */
@RestController
@RequestMapping("/api/v1/upstream")
public class UpstreamAdminController {

    private final UpstreamAdminService upstreamAdminService;
    private final CollectionService collectionService;

    public UpstreamAdminController(UpstreamAdminService upstreamAdminService,
                                   CollectionService collectionService) {
        this.upstreamAdminService = upstreamAdminService;
        this.collectionService = collectionService;
    }

    @GetMapping("/instances")
    public ApiResponse<List<PlatformResponse>> listInstances() {
        return ApiResponse.of(upstreamAdminService.listPlatforms(), null);
    }

    @PostMapping("/instances")
    public ApiResponse<PlatformResponse> createInstance(@Valid @RequestBody CreatePlatformRequest request) {
        return ApiResponse.of(upstreamAdminService.createPlatform(request), null);
    }

    @PutMapping("/instances/{instanceId}")
    public ApiResponse<PlatformResponse> updateInstance(@PathVariable Integer instanceId,
                                                        @Valid @RequestBody UpdatePlatformRequest request) {
        return ApiResponse.of(upstreamAdminService.updatePlatform(instanceId, request), null);
    }

    @DeleteMapping("/instances/{instanceId}")
    public ApiResponse<Void> deleteInstance(@PathVariable Integer instanceId) {
        upstreamAdminService.deletePlatform(instanceId);
        return ApiResponse.of(null, null);
    }

    @PostMapping("/instances/{instanceId}/accounts/{accountId}/collect")
    public ApiResponse<Void> collectAccount(@PathVariable Integer instanceId,
                                            @PathVariable Integer accountId) {
        collectionService.collectAccount(instanceId, accountId);
        return ApiResponse.of(null, null);
    }
    @PutMapping("/instances/{instanceId}/accounts/{accountId}")
    public ApiResponse<AccountResponse> updateAccount(@PathVariable Integer instanceId,
                                                       @PathVariable Integer accountId,
                                                       @Valid @RequestBody UpdateAccountRequest request) {
        return ApiResponse.of(upstreamAdminService.updateAccount(instanceId, accountId, request), null);
    }

    @DeleteMapping("/instances/{instanceId}/accounts/{accountId}")
    public ApiResponse<Void> deleteAccount(@PathVariable Integer instanceId,
                                           @PathVariable Integer accountId) {
        upstreamAdminService.deleteAccount(instanceId, accountId);
        return ApiResponse.of(null, null);
    }
    @GetMapping("/instances/{instanceId}/groups")
    public ApiResponse<List<UpstreamGroupResponse>> listGroups(@PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listGroups(instanceId), null);
    }

    @GetMapping("/instances/{instanceId}/usage-dashboard")
    public ApiResponse<List<AccountUsageDashboardResponse>> listUsageDashboard(
            @PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listUsageDashboard(instanceId), null);
    }

    @GetMapping("/instances/{instanceId}/changes")
    public ApiResponse<List<UpstreamChangeEventResponse>> listChanges(@PathVariable Integer instanceId,
                                                                       @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.of(upstreamAdminService.listChanges(instanceId, limit), null);
    }

    @GetMapping("/instances/{instanceId}/accounts")
    public ApiResponse<List<AccountResponse>> listAccounts(@PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listAccounts(instanceId), null);
    }

    @PostMapping("/instances/{instanceId}/accounts")
    public ApiResponse<AccountResponse> createAccount(@PathVariable Integer instanceId,
                                                       @Valid @RequestBody CreateAccountRequest request) {
        return ApiResponse.of(upstreamAdminService.createAccount(instanceId, request), null);
    }
}