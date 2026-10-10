package com.monitor.platform.bot.identity;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sub2API 平台用户邮箱的 Redis 缓存。
 *
 * <p>只存字符串，避免通用 JSON 序列化对 final 类型（record）丢失类型信息的问题。
 * 两个 Hash：邮箱 → 角色、QQ 号 → 邮箱（用于自动匹配）。</p>
 */
@Component
public class Sub2ApiUserCache {

    /** 邮箱（小写）→ 角色（admin / user）。 */
    public static final String USERS_KEY = "monitor:sub2api:users";
    /** QQ 号 → 邮箱。 */
    public static final String QQ_INDEX_KEY = "monitor:sub2api:users:by-qq";
    /** 最近一次同步时间。 */
    public static final String SYNCED_AT_KEY = "monitor:sub2api:users:synced-at";

    private static final String TEMP_SUFFIX = ":tmp";

    private final RedisTemplate<String, Object> redis;

    public Sub2ApiUserCache(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    /**
     * 用最新一次同步结果整体替换缓存。
     *
     * <p>先写临时 key 再 RENAME，避免替换过程中出现「缓存为空」的窗口：
     * 这个窗口里进来的消息会被误判成非平台用户，因此不能直接 delete + 重建。</p>
     */
    public int replaceAll(Map<String, String> emailToRole, Map<String, String> qqToEmail) {
        String tempUsers = USERS_KEY + TEMP_SUFFIX;
        String tempQq = QQ_INDEX_KEY + TEMP_SUFFIX;
        redis.delete(tempUsers);
        redis.delete(tempQq);

        if (!emailToRole.isEmpty()) {
            redis.opsForHash().putAll(tempUsers, new HashMap<>(emailToRole));
        }
        if (!qqToEmail.isEmpty()) {
            redis.opsForHash().putAll(tempQq, new HashMap<>(qqToEmail));
        }

        swap(tempUsers, USERS_KEY);
        swap(tempQq, QQ_INDEX_KEY);
        redis.opsForValue().set(SYNCED_AT_KEY, OffsetDateTime.now().toString());
        return emailToRole.size();
    }

    /** 临时 key 为空时清掉目标 key，避免残留上一次的快照。 */
    private void swap(String tempKey, String targetKey) {
        if (Boolean.TRUE.equals(redis.hasKey(tempKey))) {
            redis.rename(tempKey, targetKey);
        } else {
            redis.delete(targetKey);
        }
    }

    /** 邮箱是否在平台用户列表中。 */
    public boolean contains(String email) {
        return Emails.normalize(email)
                .map(key -> Boolean.TRUE.equals(redis.opsForHash().hasKey(USERS_KEY, key)))
                .orElse(false);
    }

    /** 邮箱对应的用户是否为管理员。 */
    public boolean isAdmin(String email) {
        return Emails.normalize(email)
                .map(key -> "admin".equalsIgnoreCase(String.valueOf(redis.opsForHash().get(USERS_KEY, key))))
                .orElse(false);
    }

    /** 按 QQ 号匹配形如 {@code 123456@qq.com} 的邮箱。 */
    public Optional<String> emailByQq(Long qq) {
        if (qq == null) {
            return Optional.empty();
        }
        Object value = redis.opsForHash().get(QQ_INDEX_KEY, String.valueOf(qq));
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /** 已缓存的用户数。 */
    public long size() {
        Long size = redis.opsForHash().size(USERS_KEY);
        return size == null ? 0L : size;
    }

    /** 缓存中角色为管理员的用户数（Sub2API 侧）。 */
    public long countAdmins() {
        return redis.opsForHash().values(USERS_KEY).stream()
                .filter(value -> "admin".equalsIgnoreCase(String.valueOf(value)))
                .count();
    }

    /** 最近一次同步时间（ISO 字符串）。 */
    public Optional<String> syncedAt() {
        Object value = redis.opsForValue().get(SYNCED_AT_KEY);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /** 清空缓存（停用或排障时用）。 */
    public void clear() {
        redis.delete(USERS_KEY);
        redis.delete(QQ_INDEX_KEY);
        redis.delete(SYNCED_AT_KEY);
    }

}