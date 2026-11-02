package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotPersonaRequest;
import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器人人设库与运行时快照。
 *
 * <p>人设只参与自然语言回复的提示词组合，不参与权限、工具挂载或数据脱敏。
 * 群聊通过 {@link BotGroupReplyPolicy#personaId()} 绑定人设。</p>
 */
@Service
public class BotPersonaService {

    private static final Logger log = LoggerFactory.getLogger(BotPersonaService.class);
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 300;
    private static final int MAX_CHARACTER_PROMPT_LENGTH = 8000;
    private static final int MAX_STYLE_PROMPT_LENGTH = 2000;

    private final BotPersonaRepository repository;

    private volatile Map<Long, BotPersonaSnapshot> snapshots = Map.of();
    private volatile BotPersonaSnapshot defaultPersona = BotPersonaSnapshot.BUILTIN;

    public BotPersonaService(BotPersonaRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        reload();
    }

    public List<BotPersonaEntity> list() {
        return repository.findAll();
    }

    public BotPersonaEntity get(Long id) {
        if (id == null) {
            throw BusinessException.of("缺少人设 ID");
        }
        BotPersonaEntity entity = repository.findById(id);
        if (entity == null) {
            throw BusinessException.of("人设不存在或已被删除：" + id);
        }
        return entity;
    }

    /** 解析群绑定的人设；为空、已删除或已禁用时回退默认人设。 */
    public BotPersonaSnapshot resolve(Long personaId) {
        if (personaId != null) {
            BotPersonaSnapshot snapshot = snapshots.get(personaId);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return defaultPersona;
    }

    public BotPersonaSnapshot defaultPersona() {
        return defaultPersona;
    }

    /** 群配置保存时校验人设是否存在且启用；null 表示使用默认人设。 */
    public boolean isSelectable(Long personaId) {
        return personaId == null || snapshots.containsKey(personaId);
    }

    @Transactional
    public BotPersonaEntity create(BotPersonaRequest request) {
        BotPersonaEntity entity = new BotPersonaEntity();
        apply(entity, request);
        entity.setVersion(1);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        repository.insert(entity);
        reload();
        log.info("新增机器人人设: id={}, name={}", entity.getId(), entity.getName());
        return get(entity.getId());
    }

    @Transactional
    public BotPersonaEntity update(Long id, BotPersonaRequest request) {
        BotPersonaEntity entity = get(id);
        if (Boolean.TRUE.equals(entity.getDefaultPersona()) && Boolean.FALSE.equals(request.enabled())) {
            throw BusinessException.of("默认人设不能禁用，请先将其他启用人设设为默认");
        }
        apply(entity, request);
        entity.setVersion((entity.getVersion() == null ? 1 : entity.getVersion()) + 1);
        entity.setUpdatedAt(OffsetDateTime.now());
        repository.update(entity);
        reload();
        log.info("更新机器人人设: id={}, name={}, version={}", entity.getId(), entity.getName(), entity.getVersion());
        return get(id);
    }

    @Transactional
    public void setDefault(Long id) {
        BotPersonaEntity entity = get(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw BusinessException.of("禁用人设不能设为默认");
        }
        repository.clearDefault();
        repository.setDefault(id);
        reload();
        log.info("机器人默认人设已切换: id={}, name={}", id, entity.getName());
    }

    @Transactional
    public void delete(Long id) {
        BotPersonaEntity entity = get(id);
        if (Boolean.TRUE.equals(entity.getDefaultPersona())) {
            throw BusinessException.of("默认人设不能删除，请先切换默认人设");
        }
        int bound = repository.countGroupBindings(id);
        if (bound > 0) {
            throw BusinessException.of("该人设仍被 " + bound + " 个群使用，请先解除群绑定");
        }
        if (!repository.delete(id)) {
            throw BusinessException.of("人设不存在或已被删除：" + id);
        }
        reload();
        log.info("删除机器人人设: id={}, name={}", id, entity.getName());
    }

    private void apply(BotPersonaEntity entity, BotPersonaRequest request) {
        if (request == null) {
            throw BusinessException.of("缺少人设配置");
        }
        String name = normalizeRequired(request.name(), "人设名称", MAX_NAME_LENGTH);
        String description = normalizeOptional(request.description(), "人设说明", MAX_DESCRIPTION_LENGTH);
        String characterPrompt = normalizeRequired(request.characterPrompt(), "角色设定", MAX_CHARACTER_PROMPT_LENGTH);
        String stylePrompt = normalizeOptional(request.stylePrompt(), "表达风格", MAX_STYLE_PROMPT_LENGTH);
        entity.setName(name);
        entity.setDescription(description);
        entity.setCharacterPrompt(characterPrompt);
        entity.setStylePrompt(stylePrompt);
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private static String normalizeRequired(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw BusinessException.of(field + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw BusinessException.of(field + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw BusinessException.of(field + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private void reload() {
        try {
            Map<Long, BotPersonaSnapshot> next = new LinkedHashMap<>();
            BotPersonaSnapshot nextDefault = BotPersonaSnapshot.BUILTIN;
            for (BotPersonaEntity entity : repository.findAll()) {
                if (!Boolean.TRUE.equals(entity.getEnabled()) || entity.getId() == null) {
                    continue;
                }
                BotPersonaSnapshot snapshot = new BotPersonaSnapshot(
                        entity.getId(),
                        entity.getName(),
                        entity.getVersion() == null ? 1 : entity.getVersion(),
                        entity.getCharacterPrompt(),
                        entity.getStylePrompt());
                next.put(entity.getId(), snapshot);
                if (Boolean.TRUE.equals(entity.getDefaultPersona())) {
                    nextDefault = snapshot;
                }
            }
            snapshots = Map.copyOf(next);
            defaultPersona = nextDefault;
            log.info("机器人人设已加载: enabled={}, default={}", snapshots.size(), defaultPersona.name());
        } catch (Exception ex) {
            log.warn("加载机器人人设失败，暂时保留上一份快照: {}", ex.getMessage());
        }
    }
}
