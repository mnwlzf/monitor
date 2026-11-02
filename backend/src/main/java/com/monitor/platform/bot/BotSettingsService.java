package com.monitor.platform.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.api.dto.BotSettingsRequest;
import com.monitor.platform.api.dto.BotSettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final int DEFAULT_ERROR_ALERT_WINDOW_MINUTES = 15;
    private static final int DEFAULT_ERROR_ALERT_MIN_COUNT = 5;
    private static final double DEFAULT_ERROR_ALERT_SPIKE_RATIO = 1.5;
    private static final int DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES = 30;
    private static final String DEFAULT_VOICE = "longanhuan_v3.1";
    private static final String DEFAULT_VOICE_STYLE = "自然、友好、简洁";
    private static final int DEFAULT_RANDOM_REPLY_PERCENT = 10;
    private static final int MAX_GROUP_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final TypeReference<List<BotGroupReplyPolicy>> GROUP_POLICIES_TYPE =
            new TypeReference<>() { };
    private static final Set<String> LEGACY_MOSS_VOICES = Set.of(
            "alex", "benjamin", "charles", "david", "anna", "bella", "claire", "diana");

    private final BotSettingsRepository repository;
    private final QqBotProperties properties;
    private final ObjectMapper objectMapper;
    private final BotPersonaService personaService;

    /** 内存快照，保存后立即替换。 */
    private volatile BotSettings current;

    @Autowired
    public BotSettingsService(BotSettingsRepository repository, QqBotProperties properties,
                              ObjectMapper objectMapper, BotPersonaService personaService) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.personaService = personaService;
    }

    /** 单元测试/旧调用方便捷构造：仅用于不涉及复杂 Jackson 配置的策略 JSON。 */
    BotSettingsService(BotSettingsRepository repository, QqBotProperties properties) {
        this(repository, properties, new ObjectMapper(), null);
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
        List<BotGroupReplyPolicy> groupPolicies = normalizeGroupPolicies(request.groupPolicies(), allowedGroups);

        BotSettingsEntity entity = repository.find();
        if (entity == null) {
            entity = new BotSettingsEntity();
            entity.setId(1);
        }
        entity.setEnabled(request.enabled());
        entity.setAllowedGroups(join(allowedGroups));
        entity.setAllowedUsers(join(request.allowedUsers()));
        entity.setPlatformGroups(join(platformGroups));
        entity.setGroupReplyPolicies(writeGroupPolicies(groupPolicies));
        entity.setRequireMention(request.requireMention() == null || request.requireMention());
        entity.setCommandPrefix(normalizePrefix(request.commandPrefix()));
        entity.setMaxReplyLength(clamp(request.maxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900));
        entity.setMemoryWindow(clamp(request.memoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
        entity.setWebSearchForNonAdmin(Boolean.TRUE.equals(request.webSearchForNonAdmin()));
        entity.setErrorAlertEnabled(Boolean.TRUE.equals(request.errorAlertEnabled()));
        entity.setErrorAlertWindowMinutes(clamp(request.errorAlertWindowMinutes(),
                1, 1440, DEFAULT_ERROR_ALERT_WINDOW_MINUTES));
        entity.setErrorAlertMinCount(clamp(request.errorAlertMinCount(),
                1, 100000, DEFAULT_ERROR_ALERT_MIN_COUNT));
        entity.setErrorAlertSpikeRatio(clamp(request.errorAlertSpikeRatio(),
                1.0, 100.0, DEFAULT_ERROR_ALERT_SPIKE_RATIO));
        entity.setErrorAlertCooldownMinutes(clamp(request.errorAlertCooldownMinutes(),
                1, 1440, DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES));
        entity.setDefaultVoice(normalizeVoice(request.defaultVoice()));
        entity.setDefaultVoiceStyle(normalizeVoiceStyle(request.defaultVoiceStyle()));
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.save(entity);

        current = toSettings(entity);
        log.info("机器人设置已更新: enabled={}, groups={}, platformGroups={}, groupPolicies={}, users={}, requireMention={}, webSearchForNonAdmin={}, errorAlertEnabled={}, errorAlertWindow={}m",
                current.enabled(), current.allowedGroups(), current.platformGroups(),
                current.groupPolicies().size(), current.allowedUsers(), current.requireMention(), current.webSearchForNonAdmin(),
                current.errorAlertEnabled(), current.errorAlertWindowMinutes());
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

    /**
     * 校验并规范化按群策略。
     *
     * <p>启用群不为空时，策略里的群号也必须是启用群的子集，避免保存永远不会生效的配置。</p>
     */
    private List<BotGroupReplyPolicy> normalizeGroupPolicies(List<BotGroupReplyPolicy> policies,
                                                              Set<String> allowedGroups) {
        if (policies == null || policies.isEmpty()) {
            return List.of();
        }
        Map<String, BotGroupReplyPolicy> normalized = new LinkedHashMap<>();
        for (BotGroupReplyPolicy policy : policies) {
            if (policy == null) {
                continue;
            }
            String groupId = policy.groupId() == null ? "" : policy.groupId().trim();
            if (groupId.isEmpty()) {
                throw BusinessException.of("群聊策略的群号不能为空");
            }
            if (groupId.length() > 32) {
                throw BusinessException.of("群聊策略的群号过长：" + groupId);
            }
            if (!allowedGroups.isEmpty() && !allowedGroups.contains(groupId)) {
                throw BusinessException.of("群聊策略必须是启用群的子集，以下群号不在启用群里：" + groupId);
            }
            if (normalized.containsKey(groupId)) {
                throw BusinessException.of("同一个群只能配置一条回复策略：" + groupId);
            }
            if (personaService != null && !personaService.isSelectable(policy.personaId())) {
                throw BusinessException.of("群聊策略绑定的人设不存在或已禁用：" + groupId);
            }
            BotGroupReplyStrategy strategy = policy.strategy() == null
                    ? BotGroupReplyStrategy.MENTION : policy.strategy();
            int randomPercent = clamp(policy.randomPercent(), 1, 100, DEFAULT_RANDOM_REPLY_PERCENT);
            List<String> keywords = normalizeKeywords(policy.keywords());
            if (strategy == BotGroupReplyStrategy.KEYWORD && keywords.isEmpty()) {
                throw BusinessException.of("关键词唤醒策略至少需要一个关键词：" + groupId);
            }
            normalized.put(groupId, new BotGroupReplyPolicy(
                    groupId, policy.personaId(), strategy, randomPercent, keywords));
        }
        return List.copyOf(normalized.values());
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String cleaned = keyword.trim().replaceAll("\\s+", " ");
            if (cleaned.length() > MAX_KEYWORD_LENGTH) {
                throw BusinessException.of("关键词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符：" + cleaned);
            }
            unique.putIfAbsent(cleaned.toLowerCase(Locale.ROOT), cleaned);
        }
        if (unique.size() > MAX_GROUP_KEYWORDS) {
            throw BusinessException.of("单个群最多配置 " + MAX_GROUP_KEYWORDS + " 个关键词");
        }
        return List.copyOf(unique.values());
    }

    private String writeGroupPolicies(List<BotGroupReplyPolicy> policies) {
        try {
            return objectMapper.writeValueAsString(policies == null ? List.of() : policies);
        } catch (Exception ex) {
            throw BusinessException.of("群聊回复策略序列化失败");
        }
    }

    private List<BotGroupReplyPolicy> readGroupPolicies(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<BotGroupReplyPolicy> policies = objectMapper.readValue(json, GROUP_POLICIES_TYPE);
            return policies == null ? List.of() : List.copyOf(policies);
        } catch (Exception ex) {
            log.warn("解析群聊回复策略失败，按空策略处理: {}", ex.getMessage());
            return List.of();
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
        entity.setGroupReplyPolicies("[]");
        entity.setRequireMention(properties.isRequireMention());
        entity.setCommandPrefix(normalizePrefix(properties.getCommandPrefix()));
        entity.setMaxReplyLength(clamp(properties.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900));
        entity.setMemoryWindow(clamp(properties.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10));
        // 普通人联网搜索默认关闭，首次初始化后由页面维护
        entity.setWebSearchForNonAdmin(false);
        entity.setErrorAlertEnabled(false);
        entity.setErrorAlertWindowMinutes(DEFAULT_ERROR_ALERT_WINDOW_MINUTES);
        entity.setErrorAlertMinCount(DEFAULT_ERROR_ALERT_MIN_COUNT);
        entity.setErrorAlertSpikeRatio(DEFAULT_ERROR_ALERT_SPIKE_RATIO);
        entity.setErrorAlertCooldownMinutes(DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES);
        entity.setDefaultVoice(DEFAULT_VOICE);
        entity.setDefaultVoiceStyle(DEFAULT_VOICE_STYLE);
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
                List.of(),
                properties.isRequireMention(),
                normalizePrefix(properties.getCommandPrefix()),
                clamp(properties.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(properties.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10),
                false,
                false,
                DEFAULT_ERROR_ALERT_WINDOW_MINUTES,
                DEFAULT_ERROR_ALERT_MIN_COUNT,
                DEFAULT_ERROR_ALERT_SPIKE_RATIO,
                DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES,
                DEFAULT_VOICE,
                DEFAULT_VOICE_STYLE);
    }

    private BotSettings toSettings(BotSettingsEntity entity) {
        return new BotSettings(
                !Boolean.FALSE.equals(entity.getEnabled()),
                split(entity.getAllowedGroups()),
                split(entity.getAllowedUsers()),
                split(entity.getPlatformGroups()),
                readGroupPolicies(entity.getGroupReplyPolicies()),
                !Boolean.FALSE.equals(entity.getRequireMention()),
                normalizePrefix(entity.getCommandPrefix()),
                clamp(entity.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(entity.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10),
                Boolean.TRUE.equals(entity.getWebSearchForNonAdmin()),
                Boolean.TRUE.equals(entity.getErrorAlertEnabled()),
                clamp(entity.getErrorAlertWindowMinutes(), 1, 1440, DEFAULT_ERROR_ALERT_WINDOW_MINUTES),
                clamp(entity.getErrorAlertMinCount(), 1, 100000, DEFAULT_ERROR_ALERT_MIN_COUNT),
                clamp(entity.getErrorAlertSpikeRatio(), 1.0, 100.0, DEFAULT_ERROR_ALERT_SPIKE_RATIO),
                clamp(entity.getErrorAlertCooldownMinutes(), 1, 1440, DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES),
                normalizeVoice(entity.getDefaultVoice()),
                normalizeVoiceStyle(entity.getDefaultVoiceStyle()));
    }

    private BotSettingsResponse toResponse(BotSettingsEntity entity) {
        return new BotSettingsResponse(
                !Boolean.FALSE.equals(entity.getEnabled()),
                split(entity.getAllowedGroups()),
                split(entity.getAllowedUsers()),
                split(entity.getPlatformGroups()),
                readGroupPolicies(entity.getGroupReplyPolicies()),
                !Boolean.FALSE.equals(entity.getRequireMention()),
                normalizePrefix(entity.getCommandPrefix()),
                clamp(entity.getMaxReplyLength(), MIN_REPLY_LENGTH, MAX_REPLY_LENGTH, 900),
                clamp(entity.getMemoryWindow(), MIN_MEMORY_WINDOW, MAX_MEMORY_WINDOW, 10),
                Boolean.TRUE.equals(entity.getWebSearchForNonAdmin()),
                Boolean.TRUE.equals(entity.getErrorAlertEnabled()),
                clamp(entity.getErrorAlertWindowMinutes(), 1, 1440, DEFAULT_ERROR_ALERT_WINDOW_MINUTES),
                clamp(entity.getErrorAlertMinCount(), 1, 100000, DEFAULT_ERROR_ALERT_MIN_COUNT),
                clamp(entity.getErrorAlertSpikeRatio(), 1.0, 100.0, DEFAULT_ERROR_ALERT_SPIKE_RATIO),
                clamp(entity.getErrorAlertCooldownMinutes(), 1, 1440, DEFAULT_ERROR_ALERT_COOLDOWN_MINUTES),
                normalizeVoice(entity.getDefaultVoice()),
                normalizeVoiceStyle(entity.getDefaultVoiceStyle()),
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

    private static String normalizeVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return DEFAULT_VOICE;
        }
        String value = voice.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("fnlp/moss-ttsd") || LEGACY_MOSS_VOICES.contains(lower)) {
            return DEFAULT_VOICE;
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String normalizeVoiceStyle(String style) {
        if (style == null || style.isBlank()) {
            return DEFAULT_VOICE_STYLE;
        }
        String value = style.trim().replaceAll("\\s+", " ");
        return value.length() > 80 ? value.substring(0, 80) : value;
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

    private static double clamp(Double value, double min, double max, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
