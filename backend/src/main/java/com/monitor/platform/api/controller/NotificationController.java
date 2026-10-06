package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.MailRecipientRequest;
import com.monitor.platform.api.dto.MailRecipientResponse;
import com.monitor.platform.api.dto.NotificationSettingsRequest;
import com.monitor.platform.api.dto.NotificationSettingsResponse;
import com.monitor.platform.api.service.NotificationAdminService;
import com.monitor.platform.common.response.ApiResponse;
import com.monitor.platform.mail.MailScene;
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
 * 邮件通知设置接口（余额提醒开关/阈值/间隔，以及按事件区分的收件人）。
 *
 * <p>仅 ADMIN 可访问（见 {@code SecurityConfig} 的 {@code /api/v1/settings/**} 规则）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings/notification")
public class NotificationController {

    private final NotificationAdminService notificationAdminService;

    public NotificationController(NotificationAdminService notificationAdminService) {
        this.notificationAdminService = notificationAdminService;
    }

    /** 读取通知设置。 */
    @GetMapping
    public ApiResponse<NotificationSettingsResponse> getSettings() {
        return ApiResponse.of(notificationAdminService.getSettings(), null);
    }

    /** 保存通知设置。 */
    @PutMapping
    public ApiResponse<NotificationSettingsResponse> saveSettings(
            @Valid @RequestBody NotificationSettingsRequest request) {
        return ApiResponse.of(notificationAdminService.saveSettings(request), null);
    }

    /** 查询指定事件场景的收件人。 */
    @GetMapping("/recipients")
    public ApiResponse<List<MailRecipientResponse>> listRecipients(@RequestParam MailScene scene) {
        return ApiResponse.of(notificationAdminService.listRecipients(scene), null);
    }

    /** 新增指定事件的收件人。 */
    @PostMapping("/recipients")
    public ApiResponse<MailRecipientResponse> addRecipient(
            @Valid @RequestBody MailRecipientRequest request) {
        return ApiResponse.of(notificationAdminService.addRecipient(request), null);
    }

    /** 删除收件人。 */
    @DeleteMapping("/recipients/{recipientId}")
    public ApiResponse<Void> deleteRecipient(@PathVariable Long recipientId) {
        notificationAdminService.deleteRecipient(recipientId);
        return ApiResponse.of(null, null);
    }
}