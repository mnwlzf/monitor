package com.monitor.platform.bot.identity;

import com.monitor.platform.api.dto.BotAdminResponse;
import com.monitor.platform.api.dto.BotIdentityOverviewResponse;
import com.monitor.platform.bot.BotAdminEntity;
import com.monitor.platform.bot.BotAdminService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 身份识别相关的页面服务：总览、手动同步、自定义管理员增删。
 *
 * <p>把「页面展示」和「消息热路径」分开：这里的方法只在用户操作页面时调用，
 * 机器人每条消息走的是 {@link BotIdentityResolver} 的内存/Redis 快路径。</p>
 */
@Service
public class BotIdentityAdminService {

    private final BotIdentityProperties properties;
    private final Sub2ApiUserReader reader;
    private final Sub2ApiUserCache cache;
    private final Sub2ApiUserDirectory directory;
    private final BotAdminService botAdmins;

    public BotIdentityAdminService(BotIdentityProperties properties,
                                   Sub2ApiUserReader reader,
                                   Sub2ApiUserCache cache,
                                   Sub2ApiUserDirectory directory,
                                   BotAdminService botAdmins) {
        this.properties = properties;
        this.reader = reader;
        this.cache = cache;
        this.directory = directory;
        this.botAdmins = botAdmins;
    }

    /** 页面总览：Sub2API 侧缓存情况 + 自定义管理员名单。 */
    public BotIdentityOverviewResponse overview() {
        BotIdentityOverviewResponse.Sub2ApiUsers sub2Api = new BotIdentityOverviewResponse.Sub2ApiUsers(
                reader.isAvailable(),
                cache.size(),
                cache.countAdmins(),
                cache.syncedAt().orElse(null));

        List<BotAdminResponse> customAdmins = botAdmins.list().stream()
                .map(this::toResponse)
                .toList();

        return new BotIdentityOverviewResponse(
                properties.isEnabled(),
                properties.isQqLocalPartMatch(),
                sub2Api,
                customAdmins);
    }

    /** 手动触发一次 Sub2API 用户同步，并返回刷新后的总览。 */
    public BotIdentityOverviewResponse syncNow() {
        directory.sync();
        return overview();
    }

    /** 新增自定义管理员。 */
    public BotAdminResponse addAdmin(String email, String remark) {
        return toResponse(botAdmins.add(email, remark));
    }

    /** 删除自定义管理员。 */
    public void removeAdmin(Long id) {
        botAdmins.remove(id);
    }

    private BotAdminResponse toResponse(BotAdminEntity entity) {
        return new BotAdminResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getRemark(),
                entity.getCreatedAt());
    }
}