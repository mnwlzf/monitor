package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.MailRecipientRequest;
import com.monitor.platform.api.dto.MailRecipientResponse;
import com.monitor.platform.api.dto.NotificationSettingsRequest;
import com.monitor.platform.api.dto.NotificationSettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.mail.MailRecipientEntity;
import com.monitor.platform.mail.MailRecipientRepository;
import com.monitor.platform.mail.NotificationSettingsEntity;
import com.monitor.platform.mail.NotificationSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 邮件通知设置管理服务。
 *
 * <p>负责余额提醒开关/阈值，以及收件人（一个或多个）的增删查。</p>
 */
@Service
public class NotificationAdminService {

    private final NotificationSettingsRepository notificationSettingsRepository;
    private final MailRecipientRepository mailRecipientRepository;

    public NotificationAdminService(NotificationSettingsRepository notificationSettingsRepository,
                                    MailRecipientRepository mailRecipientRepository) {
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.mailRecipientRepository = mailRecipientRepository;
    }

    /** 读取通知设置。 */
    public NotificationSettingsResponse getSettings() {
        return toResponse(notificationSettingsRepository.get());
    }

    /** 保存通知设置。 */
    @Transactional
    public NotificationSettingsResponse saveSettings(NotificationSettingsRequest request) {
        NotificationSettingsEntity entity = notificationSettingsRepository.get();
        if (request.balanceAlertEnabled() != null) {
            entity.setBalanceAlertEnabled(request.balanceAlertEnabled());
        }
        if (request.balanceThreshold() != null) {
            entity.setBalanceThreshold(request.balanceThreshold());
        }
        if (request.alertIntervalMinutes() != null) {
            entity.setAlertIntervalMinutes(request.alertIntervalMinutes());
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        notificationSettingsRepository.save(entity);
        return toResponse(entity);
    }

    /** 查询全部收件人。 */
    public List<MailRecipientResponse> listRecipients() {
        return mailRecipientRepository.findAll().stream().map(this::toResponse).toList();
    }

    /** 新增收件人，邮箱不可重复。 */
    @Transactional
    public MailRecipientResponse addRecipient(MailRecipientRequest request) {
        String email = request.email().trim();
        mailRecipientRepository.findByEmail(email).ifPresent(existing -> {
            throw BusinessException.of("收件人已存在: " + email);
        });

        MailRecipientEntity entity = new MailRecipientEntity();
        entity.setEmail(email);
        entity.setName(trimToNull(request.name()));
        entity.setCreatedAt(OffsetDateTime.now());
        mailRecipientRepository.save(entity);
        return toResponse(entity);
    }

    /** 删除收件人。 */
    @Transactional
    public void deleteRecipient(Long recipientId) {
        mailRecipientRepository.deleteById(recipientId);
    }

    private NotificationSettingsResponse toResponse(NotificationSettingsEntity entity) {
        return new NotificationSettingsResponse(
                Boolean.TRUE.equals(entity.getBalanceAlertEnabled()),
                entity.getBalanceThreshold() == null
                        ? NotificationSettingsRepository.DEFAULT_THRESHOLD
                        : entity.getBalanceThreshold(),
                entity.getAlertIntervalMinutes() == null || entity.getAlertIntervalMinutes() <= 0
                        ? NotificationSettingsRepository.DEFAULT_ALERT_INTERVAL_MINUTES
                        : entity.getAlertIntervalMinutes(),
                mailRecipientRepository.findAll().size(),
                entity.getUpdatedAt());
    }

    private MailRecipientResponse toResponse(MailRecipientEntity entity) {
        return new MailRecipientResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getName(),
                entity.getCreatedAt());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}