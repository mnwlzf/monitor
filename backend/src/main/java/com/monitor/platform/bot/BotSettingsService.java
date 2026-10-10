package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotSettingsRequest;
import com.monitor.platform.api.dto.BotSettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 页面可配置的机器人设置。
 *
 * <p>数据库里的 {@code bot_settings} 单行是唯一事实来源；这一行不存在时，
 * 用环境变量（{@link QqBotProperties}）初始化一行，保证老部署平滑过渡。</p>
 *
 * <p>设置保存在内存快照里，页面保存后立即刷新，机器人下一条消息就用新配置，**不需要重启**。</p>
 *
 * <p>「指定群」（{@code platformGroups}）是「启用群」（{@code allowedGroups}）的子集，
 * 保存时校验；启用群为空（不限制）时跳过子集校验。</p>
 */
@Service
public class BotSettingsService {

    private static final Logger log = LoggerFactory.getLogger(BotSettingsService.class);

    private static final int MIN_REPLY_LENGTH = 50;
    private static final int MAX_REPLY_LENGTH = 4000;
    private static final int MIN_MEMORY_WINDOW = 2;
    private static final int MAX_MEMORY_WINDOW = 50;

    private final BotSettingsRepository repository;
    private final QqBotProperties properties;

    /** 内存快照，保存后立即替换。 */
    private volatile BotSettings current;

    public BotSettingsService(BotSettingsRepository repository, QqBotProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        try {
            reload();
        } catch (Exception ex) {
            log.warn("加载机器人设置失败，暂时回退到环境变量: {}", ex.getMessage());
            current = fromProperties();
        }
    }

    /** 当前生效的设置（热路径，读内存）。 */
    public BotSettings current() {
        BotSettings snapshot = current;
        if (snapshot == null) {
            snapshot = fromProperties();
            current = snapshot;
        }
        return snapshot;
    }

    /** 读取设置（页面用）。 */
    public BotSettingsResponse get() {
        BotSettingsEntity entity = repository.find();
        if (entity == null) {
            entity = seed();
        }
        return toResponse(entity);
    }

    /** 保存设置并立即刷新内存快照。 */
    @Transactional
    public BotSettingsResponse save(BotSettingsRequest request) {
        Set<String> allowedGroups = split(join(request.allowedGroups()));
        Set<String> platformGroups = split(join(request.platformGroups()));
        validateSubset(allowedGroups, platformGroups);

        BotSettingsEntity entity = repository.find();
        if (entity == null) {
            entity = new BotSettingsEntity();
            entity.setId(1);
        }
        entity.setEnabled(request.enabled());
        entity.setAllowedGroups(join(allowedGroups));
        entity.setAllowedUsers(join(request.allowedUsers()));
        entity.setPlatformGroups(join(platformGroups));
        entity.setRequireMention(request.requireMention() == null || request.requireMention());
        entity.setCommandPrefix(normalizePrefix(request.commandPrefix()));
        entity.setMaxReplyLength(clamp(request.maxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900));
        entity.setMemoryWindow(clamp(request.memoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);

        current = toSettings(entity);
        log.info("机器人设置已更新: enabled={}, groups={}, platformGroups={}, users={}, requireMention={}",
                current.enabled(), current.allowedGroups(), current.platformGroups(),
                current.allowedUsers(), current.requireMention());
        return toResponse(entity);
    }

    /** 「指定群」必须是「启用群」的子集；启用群为空（不限制）时不校验。 */
    private static void validateSubset(Set<String> allowedGroups, Set<String> platformGroups) {
        if (allowedGroups.isEmpty() || platformGroups.isEmpty()) {
            return;
        }
        for (String group : platformGroups) {
            if (!allowedGroups.contains(group)) {
                throw BusinessException.of("指定群必须是启用群的子集，以下群号不在启用群里：" + group);
            }
        }
    }

    /** 从数据库重新加载；没有这一行时用环境变量初始化一行。 */
    void reload() {
        BotSettingsEntity entity = repository.find();
        if (entity == null) {
            entity = seed();
        }
        current = toSettings(entity);
    }

    /** 首次运行时按环境变量落一行，之后就以数据库为准。 */
    private BotSettingsEntity seed() {
        BotSettingsEntity entity = new BotSettingsEntity();
        entity.setId(1);
        // 部署级总开关在环境变量；这里是运行时开关，默认开，避免升级后机器人「莫名不回消息」
        entity.setEnabled(true);
        entity.setAllowedGroups(join(properties.getAllowedGroups()));
        entity.setAllowedUsers(join(properties.getAllowedUsers()));
        // 指定群没有环境变量来源，首次初始化为空（不给任何群开平台功能）
        entity.setPlatformGroups(null);
        entity.setRequireMention(properties.isRequireMention());
        entity.setCommandPrefix(normalizePrefix(properties.getCommandPrefix()));
        entity.setMaxReplyLength(clamp(properties.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900));
        entity.setMemoryWindow(clamp(properties.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);
        log.info("已用环境变量初始化机器人设置（之后请在页面上维护）: groups={}, users={}",
                entity.getAllowedGroups(), entity.getAllowedUsers());
        return entity;
    }

    private BotSettings fromProperties() {
        return new BotSettings(
                true,
                split(join(properties.getAllowedGroups())),
                split(join(properties.getAllowedUsers())),
                Set.of(),
                properties.isRequireMention(),
                normalizePrefix(properties.getCommandPrefix()),
                clamp(properties.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(properties.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
    }

    private BotSettings toSettings(BotSettingsEntity entity) {
        return new BotSettings(
                !Boolean.FALSE.equals(entity.getEnabled()),
                split(entity.getAllowedGroups()),
                split(entity.getAllowedUsers()),
                split(entity.getPlatformGroups()),
                !Boolean.FALSE.equals(entity.getRequireMention()),
                normalizePrefix(entity.getCommandPrefix()),
                clamp(entity.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(entity.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
    }

    private BotSettingsResponse toResponse(BotSettingsEntity entity) {
        return new BotSettingsResponse(
                !Boolean.FALSE.equals(entity.getEnabled()),
                split(entity.getAllowedGroups()),
                split(entity.getAllowedUsers()),
                split(entity.getPlatformGroups()),
                !Boolean.FALSE.equals(entity.getRequireMention()),
                normalizePrefix(entity.getCommandPrefix()),
                clamp(entity.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(entity.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10),
                entity.getUpdatedAt());
    }

    private static Set<String> split(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private static String join(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/";
        }
        String trimmed = prefix.trim();
        return trimmed.length() > 8 ? trimmed.substring(0, 8) : trimmed;
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}