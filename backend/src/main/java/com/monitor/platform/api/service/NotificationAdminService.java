package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.MailRecipientRequest;
import com.monitor.platform.api.dto.MailRecipientResponse;
import com.monitor.platform.api.dto.NotificationSettingsRequest;
import com.monitor.platform.api.dto.NotificationSettingsResponse;
import com.monitor.platform.api.dto.QqRecipientRequest;
import com.monitor.platform.api.dto.QqRecipientResponse;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.mail.MailRecipientEntity;
import com.monitor.platform.mail.MailRecipientRepository;
import com.monitor.platform.mail.MailScene;
import com.monitor.platform.mail.NotificationSettingsEntity;
import com.monitor.platform.mail.NotificationSettingsRepository;
import com.monitor.platform.notify.QqRecipientEntity;
import com.monitor.platform.notify.QqRecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 通知设置管理服务。
 *
 * <p>负责余额提醒开关/阈值/间隔，以及按事件场景维护<strong>两个通道</strong>的接收人：
 * 邮件收件人（{@code mail_recipients}）与 QQ 消息接收人（{@code qq_recipients}）。
 * 两者互不依赖 —— 只配 QQ 也能收到提醒。</p>
 */
@Service
public class NotificationAdminService {

    private final NotificationSettingsRepository notificationSettingsRepository;
    private final MailRecipientRepository mailRecipientRepository;
    private final QqRecipientRepository qqRecipientRepository;

    public NotificationAdminService(NotificationSettingsRepository notificationSettingsRepository,
                                    MailRecipientRepository mailRecipientRepository,
                                    QqRecipientRepository qqRecipientRepository) {
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.mailRecipientRepository = mailRecipientRepository;
        this.qqRecipientRepository = qqRecipientRepository;
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

    /** 查询指定事件场景下的收件人。 */
    public List<MailRecipientResponse> listRecipients(MailScene scene) {
        return mailRecipientRepository.findByScene(scene).stream().map(this::toResponse).toList();
    }

    /** 新增收件人，同一场景下邮箱不可重复。 */
    @Transactional
    public MailRecipientResponse addRecipient(MailRecipientRequest request) {
        String email = request.email().trim();
        mailRecipientRepository.findBySceneAndEmail(request.scene(), email).ifPresent(existing -> {
            throw BusinessException.of("该事件下收件人已存在: " + email);
        });

        MailRecipientEntity entity = new MailRecipientEntity();
        entity.setScene(request.scene().name());
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

    /** 查询指定事件场景下配置的 QQ 消息接收人。 */
    public List<QqRecipientResponse> listQqRecipients(MailScene scene) {
        return qqRecipientRepository.findByScene(scene).stream().map(this::toResponse).toList();
    }

    /** 新增 QQ 消息接收人，同一场景下同一个群 / 私聊不可重复。 */
    @Transactional
    public QqRecipientResponse addQqRecipient(QqRecipientRequest request) {
        qqRecipientRepository.findBySceneAndTarget(request.scene(), request.targetType(), request.targetId())
                .ifPresent(existing -> {
                    throw BusinessException.of("该事件下已经配置过这个接收人: "
                            + request.targetType().label() + " " + request.targetId());
                });

        QqRecipientEntity entity = new QqRecipientEntity();
        entity.setScene(request.scene().name());
        entity.setTargetType(request.targetType().name());
        entity.setTargetId(request.targetId());
        entity.setName(trimToNull(request.name()));
        entity.setCreatedAt(OffsetDateTime.now());
        qqRecipientRepository.save(entity);
        return toResponse(entity);
    }

    /** 删除 QQ 消息接收人。 */
    @Transactional
    public void deleteQqRecipient(Long recipientId) {
        qqRecipientRepository.deleteById(recipientId);
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
                entity.getUpdatedAt());
    }

    private MailRecipientResponse toResponse(MailRecipientEntity entity) {
        return new MailRecipientResponse(
                entity.getId(),
                entity.getScene(),
                entity.getEmail(),
                entity.getName(),
                entity.getCreatedAt());
    }

    private QqRecipientResponse toResponse(QqRecipientEntity entity) {
        return new QqRecipientResponse(
                entity.getId(),
                entity.getScene(),
                entity.getTargetType(),
                entity.getTargetId(),
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