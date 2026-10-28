package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BotLotteryDrawResponse;
import com.monitor.platform.api.dto.BotLotterySettingsRequest;
import com.monitor.platform.api.dto.BotLotterySettingsResponse;
import com.monitor.platform.bot.BotLotteryDrawRepository;
import com.monitor.platform.bot.BotLotterySettingsService;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 抽奖活动设置与记录接口，仅 ADMIN 可访问。 */
@RestController
@RequestMapping("/api/v1/settings/lottery")
public class BotLotteryController {

    private final BotLotterySettingsService settingsService;
    private final BotLotteryDrawRepository drawRepository;

    public BotLotteryController(BotLotterySettingsService settingsService,
                                BotLotteryDrawRepository drawRepository) {
        this.settingsService = settingsService;
        this.drawRepository = drawRepository;
    }

    @GetMapping
    public ApiResponse<BotLotterySettingsResponse> getSettings() {
        return ApiResponse.of(settingsService.get(), null);
    }

    @PutMapping
    public ApiResponse<BotLotterySettingsResponse> saveSettings(@RequestBody BotLotterySettingsRequest request) {
        return ApiResponse.of(settingsService.save(request), null);
    }

    @GetMapping("/draws")
    public ApiResponse<List<BotLotteryDrawResponse>> recentDraws(
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        int size = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        return ApiResponse.of(drawRepository.recent(size).stream().map(BotLotteryDrawResponse::from).toList(), null);
    }
}
