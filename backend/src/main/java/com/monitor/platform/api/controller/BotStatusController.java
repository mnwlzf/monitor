package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotStatusResponse;
import com.monitor.platform.api.service.BotStatusService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人运行状态接口（「机器人状态」页面用）。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/bot")
public class BotStatusController {

    private final BotStatusService botStatusService;

    public BotStatusController(BotStatusService botStatusService) {
        this.botStatusService = botStatusService;
    }

    /** 读取机器人当前运行状态（含一次 OneBot 实时探活）。 */
    @GetMapping("/status")
    public ApiResponse<BotStatusResponse> status() {
        return ApiResponse.of(botStatusService.status(), null);
    }
}
