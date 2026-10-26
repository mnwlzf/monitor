package com.monitor.platform.bot.key;

import com.monitor.platform.adapter.sub2api.Sub2ApiAdapter;
import com.monitor.platform.adapter.sub2api.model.Sub2GroupsResponse;
import com.monitor.platform.adapter.sub2api.model.Sub2KeysResponse;
import com.monitor.platform.collector.application.AccountCredentialService;
import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理员的 Sub2API 建密钥工具。
 *
 * <p><strong>只挂给管理员人设</strong>（{@code MONITOR_ADMIN}）。在「号池监控源」平台
 * （用户自建的 Sub2API）上，用该平台账号的登录令牌调用 {@code POST /api/v1/keys} 创建密钥。</p>
 *
 * <p>五要素全部必填：厂商（分组 platform）、名称、分组、额度限制、有效期。缺任何一项都不调用
 * Sub2API，直接把缺项告诉管理员补。创建成功后把完整密钥和五项限制一起回给管理员 ——
 * 明文密钥<strong>只出现在回复里，不写日志</strong>。</p>
 */
@Component
public class Sub2KeyTools {

    private static final Logger log = LoggerFactory.getLogger(Sub2KeyTools.class);

    private static final int MAX_GROUP_HINTS = 30;
    private static final DateTimeFormatter EXPIRES_AT_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final AccountCredentialService credentialService;
    private final Sub2ApiAdapter sub2ApiAdapter;
    private final Sub2ApiUserCache userCache;

    public Sub2KeyTools(PlatformRepository platformRepository,
                        AccountRepository accountRepository,
                        AccountCredentialService credentialService,
                        Sub2ApiUserCache userCache,
                        Sub2ApiAdapter sub2ApiAdapter) {
        this.userCache = userCache;
        this.platformRepository = platformRepository;
        this.accountRepository = accountRepository;
        this.credentialService = credentialService;
        this.sub2ApiAdapter = sub2ApiAdapter;
    }

    @Tool(description = """
            仅管理员可用：在本平台自己的 Sub2API 上创建 API Key。
            以下 5 个业务参数全部必填，缺任何一个都【不要调用本工具】：
            - vendor：密钥厂商（分组 platform，如 openai / anthropic / gemini / grok / kimi / deepseek）
            - name：密钥名称
            - group：分组名称
            - quotaUsd：额度限制（美元，0 = 不限）
            - 有效期：expiresInDays 或 expiresAt 二选一
              * expiresInDays：有效天数；0 = 永久
              * expiresAt：精确到期时间，格式必须是 yyyy-MM-dd HH:mm:ss，且必须晚于当前时间
            创建成功后，必须把返回的完整密钥，以及厂商 / 名称 / 分组 / 额度 / 有效期全部原样回复给管理员。
            """)
    public String createSub2ApiKey(String vendor,
                                   String name,
                                   String group,
                                   @ToolParam(required = false, description = "额度限制（USD）；0 表示不限") Double quotaUsd,
                                   @ToolParam(required = false, description = "有效期天数；0 表示永久；与 expiresAt 二选一") Integer expiresInDays,
                                   @ToolParam(required = false, description = "精确到期时间，格式 yyyy-MM-dd HH:mm:ss；与 expiresInDays 二选一") String expiresAt) {
        List<String> missing = new ArrayList<>();
        if (isBlank(vendor)) {
            missing.add("密钥厂商 vendor（如 openai / anthropic / gemini）");
        }
        if (isBlank(name)) {
            missing.add("名称 name");
        }
        if (isBlank(group)) {
            missing.add("分组 group");
        }
        if (quotaUsd == null) {
            missing.add("额度限制 quotaUsd（美元，0 = 不限）");
        }
        boolean hasDays = expiresInDays != null;
        boolean hasExpiresAt = !isBlank(expiresAt);
        if (!hasDays && !hasExpiresAt) {
            missing.add("有效期 expiresInDays 或 expiresAt（二选一）");
        }
        if (!missing.isEmpty()) {
            return "还缺少以下参数，请管理员补充后再创建：\n- " + String.join("\n- ", missing);
        }
        if (hasDays && hasExpiresAt) {
            return "有效期只能二选一：expiresInDays 或 expiresAt。";
        }
        if (quotaUsd < 0) {
            return "额度限制不能为负数（0 表示不限）。";
        }
        if (hasDays && expiresInDays < 0) {
            return "有效期天数不能为负数（0 表示永久）。";
        }
        OffsetDateTime parsedExpiresAt = null;
        if (hasExpiresAt) {
            try {
                parsedExpiresAt = parseExpiresAt(expiresAt);
            } catch (IllegalArgumentException ex) {
                return ex.getMessage();
            }
            if (!parsedExpiresAt.isAfter(OffsetDateTime.now(BusinessTime.ZONE))) {
                return "有效期必须晚于当前时间（Asia/Shanghai）。";
            }
        }

        try {
            Context context = resolveContext();
            List<Sub2GroupsResponse.Group> groups = fetchGroups(context);
            List<Sub2GroupsResponse.Group> matched = groups.stream()
                    .filter(item -> same(item.name(), group))
                    .filter(item -> same(item.platform(), vendor))
                    .toList();
            if (matched.isEmpty()) {
                return notFoundMessage(vendor, group, groups);
            }
            if (matched.size() > 1) {
                return "匹配到多个分组，请确认后重试：" + matched.stream()
                        .map(item -> item.platform() + "/" + item.name() + "（ID " + item.id() + "）")
                        .collect(Collectors.joining("、"));
            }

            Sub2GroupsResponse.Group target = matched.get(0);
            Sub2KeysResponse.KeyItem created = sub2ApiAdapter.createKey(
                    context.baseUrl(), context.email(), context.password(),
                    name.trim(), target.id(), quotaUsd,
                    hasDays && expiresInDays > 0 ? expiresInDays : null, parsedExpiresAt);
            return formatSuccess(vendor.trim(), name.trim(), target, quotaUsd, expiresInDays, parsedExpiresAt, created);
        } catch (Exception ex) {
            log.warn("创建 Sub2API 密钥失败: vendor={}, group={}, reason={}", vendor, group, ex.getMessage());
            return "创建密钥失败：" + ex.getMessage();
        }
    }

    /**
     * 定位号池监控源平台与账号，并准备好登录凭证。
     *
     * <p>密码账号返回解密后的密码；手动 Token 账号先把 token 导入采集链路共用的缓存，
     * 返回 null（{@link Sub2ApiAdapter} 会直接用缓存里的 token）。</p>
     */
    private Context resolveContext() {
        PlatformEntity platform = platformRepository.findEnabled().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "没有配置「号池监控源」平台（用户自建的 Sub2API），请先在平台页面勾选"));

        List<AccountEntity> accounts = accountRepository.findEnabledByPlatformId(platform.getId());
        List<String> adminEmails = userCache.adminEmails();
        if (adminEmails.isEmpty()) {
            throw new IllegalStateException(
                    "Redis 缓存 monitor:sub2api:users 中没有 Sub2API 管理员，请先在身份识别页面同步用户");
        }

        for (String adminEmail : adminEmails) {
            AccountEntity matched = accounts.stream()
                    .filter(account -> adminEmail.equalsIgnoreCase(account.getEmail()))
                    .findFirst()
                    .orElse(null);

            if (matched != null) {
                try {
                    String password = preparePassword(platform, matched);
                    log.info("建密钥使用 Sub2API 系统管理员账号: platformId={}, email={}, source=account",
                            platform.getId(), adminEmail);
                    return new Context(platform.getUrl(), adminEmail, password);
                } catch (Exception ex) {
                    log.warn("准备 Sub2API 管理员账号凭证失败，尝试使用 JWT 缓存: email={}, reason={}",
                            adminEmail, ex.getMessage());
                }
            }

            if (sub2ApiAdapter.hasCachedAccessToken(platform.getUrl(), adminEmail)) {
                log.info("建密钥使用 Sub2API 系统管理员账号: platformId={}, email={}, source=redis-token",
                        platform.getId(), adminEmail);
                return new Context(platform.getUrl(), adminEmail, null);
            }

            log.warn("Sub2API 管理员既没有号池源账号凭证，也没有 JWT 缓存: platformId={}, email={}",
                    platform.getId(), adminEmail);
        }

        throw new IllegalStateException(
                "已从 Redis 找到 Sub2API 管理员，但没有可用的号池源账号凭证或 JWT 缓存，无法创建密钥");
    }

    /** 与采集链路保持一致：手动 Token 账号先导入缓存；否则读取账号密码。 */
    private String preparePassword(PlatformEntity platform, AccountEntity account) {
        if (AccountCredentialService.TOKEN.equalsIgnoreCase(account.getAuthType())) {
            String refreshToken = credentialService
                    .resolveToken(account.getId(), AccountCredentialService.REFRESH_TOKEN).orElse(null);
            String accessToken = credentialService
                    .resolveToken(account.getId(), AccountCredentialService.ACCESS_TOKEN).orElse(null);
            String rotated = sub2ApiAdapter.importManualToken(
                    platform.getUrl(), account.getEmail(), accessToken, refreshToken);
            if (rotated != null && !rotated.isBlank() && !rotated.equals(refreshToken)) {
                credentialService.saveToken(account.getId(), AccountCredentialService.REFRESH_TOKEN, rotated);
            }
            return null;
        }
        return credentialService.resolvePassword(account.getId());
    }

    private List<Sub2GroupsResponse.Group> fetchGroups(Context context) {
        Sub2GroupsResponse response = sub2ApiAdapter.fetchAvailableGroups(
                context.baseUrl(), context.email(), context.password());
        return response == null || response.data() == null ? List.of() : response.data();
    }

    private String notFoundMessage(String vendor, String group, List<Sub2GroupsResponse.Group> groups) {
        List<Sub2GroupsResponse.Group> sameName = groups.stream()
                .filter(item -> same(item.name(), group))
                .toList();
        StringBuilder message = new StringBuilder("没找到厂商「").append(vendor.trim())
                .append("」下名为「").append(group.trim()).append("」的分组。");
        if (!sameName.isEmpty()) {
            message.append("\n同名分组实际厂商：")
                    .append(sameName.stream()
                            .map(item -> item.platform() + "（ID " + item.id() + "）")
                            .collect(Collectors.joining("、")));
        } else {
            message.append("\n当前可用分组：")
                    .append(groups.stream()
                            .limit(MAX_GROUP_HINTS)
                            .map(item -> item.platform() + "/" + item.name())
                            .collect(Collectors.joining("、")));
            if (groups.size() > MAX_GROUP_HINTS) {
                message.append(" 等 ").append(groups.size()).append(" 个");
            }
        }
        return message.toString();
    }

    private String formatSuccess(String vendor, String name, Sub2GroupsResponse.Group group,
                                 double quotaUsd, Integer expiresInDays,
                                 OffsetDateTime expiresAt, Sub2KeysResponse.KeyItem created) {
        StringBuilder message = new StringBuilder("✅ 密钥创建成功\n\n");
        message.append("厂商：").append(vendor).append('\n');
        message.append("名称：").append(created.name() == null || created.name().isBlank() ? name : created.name())
                .append('\n');
        message.append("分组：").append(group.name()).append("（ID ").append(group.id());
        if (group.rateMultiplier() != null) {
            message.append("，倍率 ").append(money(BigDecimal.valueOf(group.rateMultiplier())));
        }
        message.append("）\n");
        message.append("额度限制：").append(quotaUsd <= 0
                ? "不限（$0）"
                : "$" + money(BigDecimal.valueOf(quotaUsd))).append('\n');
        if (expiresAt != null) {
            message.append("有效期：").append(expiresAt.format(EXPIRES_AT_FORMAT))
                    .append("（Asia/Shanghai）\n");
        } else if (expiresInDays != null && expiresInDays > 0) {
            message.append("有效期：").append(expiresInDays).append(" 天");
            if (created.expiresAt() != null && !created.expiresAt().isBlank()) {
                message.append("（到期：").append(created.expiresAt()).append('）');
            }
            message.append('\n');
        } else {
            message.append("有效期：永久\n");
        }
        message.append("状态：").append(created.status() == null || created.status().isBlank()
                ? "active" : created.status()).append('\n');
        message.append("密钥：").append(created.key());
        return message.toString();
    }

    private static OffsetDateTime parseExpiresAt(String value) {
        try {
            LocalDateTime local = LocalDateTime.parse(value.trim(), EXPIRES_AT_FORMAT);
            return local.atZone(BusinessTime.ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "有效期格式错误，必须是 yyyy-MM-dd HH:mm:ss，例如 2026-10-22 10:11:12。");
        }
    }

    private static String money(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 本次创建用的连接上下文。 */
    private record Context(String baseUrl, String email, String password) {
    }
}
