package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.AccountResponse;
import com.monitor.platform.api.dto.AccountApiKeyResponse;
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
import org.springframework.security.core.Authentication;
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

    /**
     * 查询所有启用的上游平台。
     *
     * @return 平台列表
     */
    @GetMapping("/instances")
    public ApiResponse<List<PlatformResponse>> listInstances() {
        return ApiResponse.of(upstreamAdminService.listPlatforms(), null);
    }

    /**
     * 新建上游平台。
     *
     * @param request 平台名称、地址和类型
     * @return 新建后的平台信息
     */
    @PostMapping("/instances")
    public ApiResponse<PlatformResponse> createInstance(@Valid @RequestBody CreatePlatformRequest request) {
        return ApiResponse.of(upstreamAdminService.createPlatform(request), null);
    }

    /**
     * 更新上游平台信息。
     *
     * @param instanceId 平台 ID
     * @param request    待更新字段
     * @return 更新后的平台信息
     */
    @PutMapping("/instances/{instanceId}")
    public ApiResponse<PlatformResponse> updateInstance(@PathVariable Integer instanceId,
                                                        @Valid @RequestBody UpdatePlatformRequest request) {
        return ApiResponse.of(upstreamAdminService.updatePlatform(instanceId, request), null);
    }

    /**
     * 软删除上游平台。平台下仍有账号时由业务层拒绝删除。
     *
     * @param instanceId 平台 ID
     */
    @DeleteMapping("/instances/{instanceId}")
    public ApiResponse<Void> deleteInstance(@PathVariable Integer instanceId) {
        upstreamAdminService.deletePlatform(instanceId);
        return ApiResponse.of(null, null);
    }

    /**
     * 手动触发指定平台账号采集。
     *
     * @param instanceId 平台 ID
     * @param accountId  账号 ID
     */
    @PostMapping("/instances/{instanceId}/accounts/{accountId}/collect")
    public ApiResponse<Void> collectAccount(@PathVariable Integer instanceId,
                                            @PathVariable Integer accountId) {
        collectionService.collectAccount(instanceId, accountId);
        return ApiResponse.of(null, null);
    }
    /**
     * 更新指定账号信息；密码为空时保留原凭证。
     *
     * @param instanceId 平台 ID
     * @param accountId  账号 ID
     * @param request    待更新字段
     * @return 更新后的账号信息
     */
    @PutMapping("/instances/{instanceId}/accounts/{accountId}")
    public ApiResponse<AccountResponse> updateAccount(@PathVariable Integer instanceId,
                                                       @PathVariable Integer accountId,
                                                       @Valid @RequestBody UpdateAccountRequest request) {
        return ApiResponse.of(upstreamAdminService.updateAccount(instanceId, accountId, request), null);
    }

    /**
     * 软删除指定账号，并停用其凭证。
     *
     * @param instanceId 平台 ID
     * @param accountId  账号 ID
     */
    @DeleteMapping("/instances/{instanceId}/accounts/{accountId}")
    public ApiResponse<Void> deleteAccount(@PathVariable Integer instanceId,
                                           @PathVariable Integer accountId) {
        upstreamAdminService.deleteAccount(instanceId, accountId);
        return ApiResponse.of(null, null);
    }
    /**
     * 查询平台下所有账号采集到的渠道/分组。
     *
     * @param instanceId 平台 ID
     * @return 渠道列表
     */
    @GetMapping("/instances/{instanceId}/groups")
    public ApiResponse<List<UpstreamGroupResponse>> listGroups(@PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listGroups(instanceId), null);
    }

    /**
     * 查询平台下所有账号的 API Key（仅脱敏信息）。
     *
     * @param instanceId 平台 ID
     * @return 密钥列表
     */
    @GetMapping("/instances/{instanceId}/api-keys")
    public ApiResponse<List<AccountApiKeyResponse>> listApiKeys(@PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listApiKeys(instanceId), null);
    }

    /**
     * 解密获取指定密钥的完整明文，仅管理员可调用。
     *
     * @param instanceId 平台 ID
     * @param accountId  账号 ID
     * @param keyId      密钥主键
     * @return 完整明文密钥
     */
    @PostMapping("/instances/{instanceId}/accounts/{accountId}/api-keys/{keyId}/reveal")
    public ApiResponse<String> revealApiKey(@PathVariable Integer instanceId,
                                            @PathVariable Integer accountId,
                                            @PathVariable Long keyId) {
        return ApiResponse.of(upstreamAdminService.revealApiKeySecret(instanceId, accountId, keyId), null);
    }

    /**
     * 解密获取账号登录密码明文，仅管理员可调用。
     *
     * @param instanceId     平台 ID
     * @param accountId      账号 ID
     * @param authentication 当前登录用户，用于审计日志
     * @return 明文密码
     */
    @PostMapping("/instances/{instanceId}/accounts/{accountId}/credential/reveal")
    public ApiResponse<String> revealAccountPassword(@PathVariable Integer instanceId,
                                                     @PathVariable Integer accountId,
                                                     Authentication authentication) {
        String operator = authentication == null ? "unknown" : authentication.getName();
        return ApiResponse.of(
                upstreamAdminService.revealAccountPassword(instanceId, accountId, operator), null);
    }

    /**
     * 查询平台下各账号的最新用量看板数据。
     *
     * @param instanceId 平台 ID
     * @return 用量看板列表
     */
    @GetMapping("/instances/{instanceId}/usage-dashboard")
    public ApiResponse<List<AccountUsageDashboardResponse>> listUsageDashboard(
            @PathVariable Integer instanceId) {
        return ApiResponse.of(upstreamAdminService.listUsageDashboard(instanceId), null);
    }

    /**
     * 查询平台下最近的渠道变更事件。
     *
     * @param instanceId 平台 ID
     * @param limit      最大返回条数
     * @return 变更事件列表
     */
    @GetMapping("/instances/{instanceId}/changes")
    public ApiResponse<List<UpstreamChangeEventResponse>> listChanges(@PathVariable Integer instanceId,
                                                                       @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.of(upstreamAdminService.listChanges(instanceId, limit), null);
    }

    /**
     * 查询平台下的账号列表。
     *
     * @param instanceId 平台 ID
     * @return 账号列表
     */
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
