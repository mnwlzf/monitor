package com.monitor.platform.bot.identity;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * QQ 号与邮箱的绑定关系（Redis Hash：QQ 号 → 邮箱）。
 *
 * <p>用户主动 {@code /绑定 <邮箱>} 后写入；识别时优先用绑定关系，
 * 没有绑定时再尝试按 QQ 号自动匹配邮箱。</p>
 */
@Component
public class QqUserBindingService {

    /** QQ 号 → 邮箱。 */
    public static final String BINDINGS_KEY = "monitor:qq:bindings";

    private final RedisTemplate<String, Object> redis;

    public QqUserBindingService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    /** 绑定或覆盖某个 QQ 的邮箱；邮箱非法时不写入。 */
    public boolean bind(Long qq, String email) {
        Optional<String> normalized = Emails.normalize(email);
        if (qq == null || normalized.isEmpty()) {
            return false;
        }
        redis.opsForHash().put(BINDINGS_KEY, String.valueOf(qq), normalized.get());
        return true;
    }

    /** 解除绑定。 */
    public void unbind(Long qq) {
        if (qq != null) {
            redis.opsForHash().delete(BINDINGS_KEY, String.valueOf(qq));
        }
    }

    /** 查询某个 QQ 绑定的邮箱。 */
    public Optional<String> emailOf(Long qq) {
        if (qq == null) {
            return Optional.empty();
        }
        Object value = redis.opsForHash().get(BINDINGS_KEY, String.valueOf(qq));
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }
}