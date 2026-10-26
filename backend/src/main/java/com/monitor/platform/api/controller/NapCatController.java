package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.NapCatLogsResponse;
import com.monitor.platform.api.dto.NapCatStatusResponse;
import com.monitor.platform.bot.napcat.NapCatLogCollector;
import com.monitor.platform.bot.napcat.NapCatWebUiClient;
import com.monitor.platform.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NapCat 登录与日志接口（「机器人状态」页面用）。
 *
 * <p>路径位于 {@code /api/v1/settings/**} 下，按现有安全规则仅 ADMIN 可访问。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/napcat")
public class NapCatController {

    private final NapCatWebUiClient client;
    private final NapCatLogCollector logCollector;

    public NapCatController(NapCatWebUiClient client, NapCatLogCollector logCollector) {
        this.client = client;
        this.logCollector = logCollector;
    }

    /** 当前 QQ 登录状态；离线时返回二维码 URL。 */
    @GetMapping("/status")
    public ApiResponse<NapCatStatusResponse> status() {
        return ApiResponse.of(client.status(), null);
    }

    /** 刷新二维码，用于二维码过期或掉线后重新扫码。 */
    @PostMapping("/qrcode/refresh")
    public ApiResponse<NapCatStatusResponse> refreshQrCode() {
        return ApiResponse.of(client.refreshQrCode(), null);
    }

    /** 最近 NapCat 日志；默认 300 行，最多 1000 行。 */
    @GetMapping("/logs")
    public ApiResponse<NapCatLogsResponse> logs(@RequestParam(defaultValue = "300") int limit) {
        return ApiResponse.of(logCollector.snapshot(limit), null);
    }
}