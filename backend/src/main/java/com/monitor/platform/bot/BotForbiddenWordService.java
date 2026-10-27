package com.monitor.platform.bot;

import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 机器人违禁词维护与热路径匹配。
 *
 * <p>页面和 QQ 管理命令共用同一个服务。词表保存在内存快照里，增删后立即刷新，
 * 下一条消息即可生效；匹配使用 NFKC 规范化并忽略大小写。</p>
 */
@Service
public class BotForbiddenWordService {

    private static final Logger log = LoggerFactory.getLogger(BotForbiddenWordService.class);
    static final int MAX_WORD_LENGTH = 100;

    private final BotForbiddenWordRepository repository;

    /** 已规范化的违禁词快照；热路径只读此字段。 */
    private volatile Set<String> normalizedWords = Set.of();

    public BotForbiddenWordService(BotForbiddenWordRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        reload();
    }

    /** 页面展示用，按添加时间排序。 */
    public List<BotForbiddenWordEntity> list() {
        return repository.findAll();
    }

    /** 输入中是否包含任一违禁词。 */
    public boolean containsForbiddenWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeForMatch(text);
        for (String word : normalizedWords) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 新增违禁词；空值、超长和重复词都会被拒绝。 */
    @Transactional
    public BotForbiddenWordEntity add(String input) {
        String word = normalizeDisplayWord(input);
        if (repository.findByWord(word) != null) {
            throw BusinessException.of("违禁词已存在：" + word);
        }

        BotForbiddenWordEntity entity = new BotForbiddenWordEntity();
        entity.setWord(word);
        try {
            repository.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.of("违禁词已存在：" + word);
        }
        reload();
        log.info("新增机器人违禁词: {}", word);
        return repository.findByWord(word);
    }

    /** 页面按 ID 删除。 */
    @Transactional
    public void remove(Long id) {
        if (id == null) {
            throw BusinessException.of("缺少违禁词 ID");
        }
        if (!repository.delete(id)) {
            throw BusinessException.of("违禁词不存在或已被删除：" + id);
        }
        reload();
        log.info("删除机器人违禁词: id={}", id);
    }

    /** QQ 管理命令按词删除。 */
    @Transactional
    public void remove(String input) {
        String word = normalizeDisplayWord(input);
        BotForbiddenWordEntity entity = repository.findByWord(word);
        if (entity == null) {
            throw BusinessException.of("违禁词不存在：" + word);
        }
        remove(entity.getId());
    }

    /** 从数据库重建内存快照；刷新失败时保留上一份快照，避免临时故障导致词表失效。 */
    void reload() {
        try {
            Set<String> words = new HashSet<>();
            for (BotForbiddenWordEntity entity : repository.findAll()) {
                if (entity.getWord() != null && !entity.getWord().isBlank()) {
                    words.add(normalizeForMatch(entity.getWord()));
                }
            }
            normalizedWords = Set.copyOf(words);
            log.info("机器人违禁词已加载: count={}", normalizedWords.size());
        } catch (Exception ex) {
            log.warn("加载机器人违禁词失败，暂时保留上一份词表: {}", ex.getMessage());
        }
    }

    private static String normalizeDisplayWord(String input) {
        if (input == null || input.isBlank()) {
            throw BusinessException.of("违禁词不能为空");
        }
        String word = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        if (word.length() > MAX_WORD_LENGTH) {
            throw BusinessException.of("违禁词不能超过 " + MAX_WORD_LENGTH + " 个字符");
        }
        return word;
    }

    private static String normalizeForMatch(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
