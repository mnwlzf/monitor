package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.MailSettingsRequest;
import com.monitor.platform.api.dto.MailSettingsResponse;
import com.monitor.platform.api.dto.SendTestMailRequest;
import com.monitor.platform.api.service.MailSettingsAdminService;
import com.monitor.platform.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面 SMTP 邮件设置接口。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/mail")
public class MailSettingsController {

    private final MailSettingsAdminService mailSettingsAdminService;

    public MailSettingsController(MailSettingsAdminService mailSettingsAdminService) {
        this.mailSettingsAdminService = mailSettingsAdminService;
    }

    /** 读取当前 SMTP 设置。 */
    @GetMapping
    public ApiResponse<MailSettingsResponse> getSettings() {
        return ApiResponse.of(mailSettingsAdminService.getSettings(), null);
    }

    /** 保存 SMTP 设置。 */
    @PutMapping
    public ApiResponse<MailSettingsResponse> saveSettings(@Valid @RequestBody MailSettingsRequest request) {
        return ApiResponse.of(mailSettingsAdminService.save(request), null);
    }

    /** 测试连接；请求体可选，传入时用表单值，否则用已保存值。 */
    @PostMapping("/test")
    public ApiResponse<Void> testConnection(@RequestBody(required = false) MailSettingsRequest request) {
        mailSettingsAdminService.testConnection(request);
        return ApiResponse.of(null, null);
    }

    /** 发送测试邮件。 */
    @PostMapping("/test-email")
    public ApiResponse<Void> sendTestEmail(@Valid @RequestBody SendTestMailRequest request) {
        mailSettingsAdminService.sendTestEmail(request);
        return ApiResponse.of(null, null);
    }
}