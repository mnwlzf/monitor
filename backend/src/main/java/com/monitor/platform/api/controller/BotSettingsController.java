package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotSettingsRequest;
import com.monitor.platform.api.dto.BotSettingsResponse;
import com.monitor.platform.bot.BotSettingsService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面可配置的 QQ 机器人设置接口。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）。
 * 保存后立即生效，不需要重启或重建容器。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/bot")
public class BotSettingsController {

    private final BotSettingsService botSettingsService;

    public BotSettingsController(BotSettingsService botSettingsService) {
        this.botSettingsService = botSettingsService;
    }

    /** 读取当前机器人设置。 */
    @GetMapping
    public ApiResponse<BotSettingsResponse> getSettings() {
        return ApiResponse.of(botSettingsService.get(), null);
    }

    /** 保存机器人设置，立即生效。 */
    @PutMapping
    public ApiResponse<BotSettingsResponse> saveSettings(@RequestBody BotSettingsRequest request) {
        return ApiResponse.of(botSettingsService.save(request), null);
    }
}