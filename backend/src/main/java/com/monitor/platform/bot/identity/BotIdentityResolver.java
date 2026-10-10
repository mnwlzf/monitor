package com.monitor.platform.bot.identity;

import com.monitor.platform.bot.BotAdminService;
import org.springframework.stereotype.Component;

/**
 * 把一条 QQ 消息的发送者解析成 {@link BotIdentity}。
 *
 * <p>顺序：显式绑定 &gt; 按 QQ 号自动匹配邮箱；拿到邮箱后再判定身份。</p>
 *
 * <h2>身份判定规则</h2>
 * <ul>
 *     <li><b>平台用户</b>：邮箱在 Sub2API 用户缓存里，<em>或</em> 是监控项目登记的自定义管理员；</li>
 *     <li><b>管理员</b>：Sub2API 里 {@code role=admin}，<em>或</em> 在自定义管理员名单里（两者取并集）。</li>
 * </ul>
 *
 * <p>两者都不命中时返回 {@link BotIdentity#guest()}，调用方据此切到「普通聊天」人设，
 * 绝不泄露平台信息。</p>
 */
@Component
public class BotIdentityResolver {

    private final BotIdentityProperties properties;
    private final QqUserBindingService bindings;
    private final Sub2ApiUserCache cache;
    private final BotAdminService botAdmins;

    public BotIdentityResolver(BotIdentityProperties properties,
                               QqUserBindingService bindings,
                               Sub2ApiUserCache cache,
                               BotAdminService botAdmins) {
        this.properties = properties;
        this.bindings = bindings;
        this.cache = cache;
        this.botAdmins = botAdmins;
    }

    public BotIdentity resolve(Long qq) {
        if (qq == null || !properties.isEnabled()) {
            return BotIdentity.guest();
        }
        String email = bindings.emailOf(qq).orElse(null);
        if (email == null && properties.isQqLocalPartMatch()) {
            email = cache.emailByQq(qq).orElse(null);
        }
        if (email == null) {
            return BotIdentity.guest();
        }

        boolean customAdmin = botAdmins.isAdmin(email);
        boolean sub2ApiUser = cache.contains(email);
        if (!customAdmin && !sub2ApiUser) {
            return BotIdentity.guest();
        }
        // 两种管理员来源取并集：Sub2API 的 role=admin，或监控项目里自定义登记
        boolean admin = customAdmin || cache.isAdmin(email);
        return new BotIdentity(true, admin, email);
    }
}