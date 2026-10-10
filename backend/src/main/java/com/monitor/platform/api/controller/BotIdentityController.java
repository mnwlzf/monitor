package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotAdminRequest;
import com.monitor.platform.api.dto.BotAdminResponse;
import com.monitor.platform.api.dto.BotIdentityOverviewResponse;
import com.monitor.platform.bot.identity.BotIdentityAdminService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QQ 机器人身份识别接口。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）。</p>
 *
 * <p>管理员身份有两个来源，<strong>取并集</strong>：Sub2API 自带的管理员角色，
 * 以及这里登记的自定义管理员（因为 Sub2API 只允许一个管理员，实际运维往往需要多人）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/bot/identity")
public class BotIdentityController {

    private final BotIdentityAdminService adminService;

    public BotIdentityController(BotIdentityAdminService adminService) {
        this.adminService = adminService;
    }

    /** 身份识别总览：Sub2API 用户缓存情况 + 自定义管理员名单。 */
    @GetMapping
    public ApiResponse<BotIdentityOverviewResponse> overview() {
        return ApiResponse.of(adminService.overview(), null);
    }

    /** 立即从 Sub2API 只读库同步一次平台用户。 */
    @PostMapping("/sync")
    public ApiResponse<BotIdentityOverviewResponse> sync() {
        return ApiResponse.of(adminService.syncNow(), null);
    }

    /** 新增自定义管理员。 */
    @PostMapping("/admins")
    public ApiResponse<BotAdminResponse> addAdmin(@RequestBody BotAdminRequest request) {
        return ApiResponse.of(adminService.addAdmin(request.email(), request.remark()), null);
    }

    /** 删除自定义管理员。 */
    @DeleteMapping("/admins/{id}")
    public ApiResponse<Void> removeAdmin(@PathVariable Long id) {
        adminService.removeAdmin(id);
        return ApiResponse.of(null, null);
    }
}