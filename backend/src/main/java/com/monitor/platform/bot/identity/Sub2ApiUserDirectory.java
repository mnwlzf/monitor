package com.monitor.platform.bot.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把自建 Sub2API 的全部平台用户同步到 Redis，供 QQ 机器人识别身份。
 *
 * <p>数据来源是 Sub2API 的只读库（{@code public.users}），与号池增量采集共用同一套连接配置。
 * 缓存里只保留邮箱与角色，不落库、不写日志。</p>
 */
@Service
public class Sub2ApiUserDirectory {

    private static final Logger log = LoggerFactory.getLogger(Sub2ApiUserDirectory.class);

    private final Sub2ApiUserReader reader;
    private final Sub2ApiUserCache cache;
    private final BotIdentityProperties properties;

    public Sub2ApiUserDirectory(Sub2ApiUserReader reader,
                                Sub2ApiUserCache cache,
                                BotIdentityProperties properties) {
        this.reader = reader;
        this.cache = cache;
        this.properties = properties;
    }

    /**
     * 同步一次平台用户并整体刷新缓存。
     *
     * @return 写入缓存的用户数；未配置只读库或上游返回空时返回 0 且不动缓存
     */
    public int sync() {
        if (!reader.isAvailable()) {
            log.debug("未配置 Sub2API 只读库，跳过平台用户同步");
            return 0;
        }

        List<Sub2ApiUser> users = reader.fetchUsers();
        if (users.isEmpty()) {
            // 上游异常时也可能返回空，此时保留上一次快照，
            // 否则一次抖动就会把所有平台用户误判成陌生人。
            log.warn("Sub2API 用户同步返回 0 条，保留上一次缓存");
            return 0;
        }

        Map<String, String> emailToRole = new LinkedHashMap<>();
        Map<String, String> qqToEmail = new LinkedHashMap<>();
        Map<String, String> emailToId = new LinkedHashMap<>();
        int skipped = 0;
        for (Sub2ApiUser user : users) {
            Optional<String> email = Emails.normalize(user.email());
            if (email.isEmpty() || !user.isActive()) {
                skipped++;
                continue;
            }
            String key = email.get();
            emailToRole.put(key, user.isAdmin() ? "admin" : "user");
            if (user.id() != null) {
                // 用户端查自己数据时要拿它去调管理端接口，缓存下来避免每条消息都查库
                emailToId.put(key, String.valueOf(user.id()));
            }
            if (properties.isQqLocalPartMatch()) {
                Emails.qqLocalPart(key).ifPresent(qq -> qqToEmail.putIfAbsent(qq, key));
            }
        }

        int size = cache.replaceAll(emailToRole, qqToEmail, emailToId);
        log.info("Sub2API 用户缓存已刷新: users={}, qqMatched={}, skipped={}",
                size, qqToEmail.size(), skipped);
        return size;
    }

    /** 当前缓存的用户数与最近同步时间，供页面/日志排查。 */
    public String describe() {
        return "缓存用户数 " + cache.size()
                + "，最近同步 " + cache.syncedAt().orElse("（从未同步）");
    }
}