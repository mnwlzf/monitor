package com.monitor.platform.mail;

import com.monitor.platform.collector.repository.AccountRepository;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把变更事件里的 {@code accountId} 解析成平台名 / 账号名，供邮件正文展示。
 *
 * <p>同一封邮件里同一个账号常被多次引用，因此解析结果通过调用方传入的 Map 缓存，
 * 避免重复查库。</p>
 */
@Component
public class AccountNameResolver {

    private final AccountRepository accountRepository;
    private final PlatformRepository platformRepository;

    public AccountNameResolver(AccountRepository accountRepository, PlatformRepository platformRepository) {
        this.accountRepository = accountRepository;
        this.platformRepository = platformRepository;
    }

    /** 账号所属平台名；账号不存在时返回「未知平台」。 */
    public String platformName(Integer accountId, Map<Integer, String> cache) {
        if (accountId == null) {
            return "未知平台";
        }
        return cache.computeIfAbsent(accountId, id -> accountRepository.findById(id)
                .map(AccountEntity::getPlatformId)
                .flatMap(platformRepository::findById)
                .map(PlatformEntity::getPlatformName)
                .orElse("未知平台"));
    }

    /** 账号展示名；账号不存在时返回「账号 + ID」。 */
    public String accountName(Integer accountId, Map<Integer, String> cache) {
        if (accountId == null) {
            return "未知账号";
        }
        return cache.computeIfAbsent(accountId, id -> accountRepository.findById(id)
                .map(MailFormat::displayName)
                .orElse("账号 " + id));
    }
}
