package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.Emails;
import com.monitor.platform.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 监控项目侧的自定义管理员。
 *
 * <p>Sub2API 只允许一个管理员账号，实际运维需要多人分担，因此在这里再维护一份名单。
 * 识别时与 Sub2API 的 {@code role=admin} <strong>取并集</strong>：任一命中即为管理员。</p>
 *
 * <p>热路径（每条 QQ 消息）读内存快照，页面增删后立即刷新，不需要重启。</p>
 */
@Service
public class BotAdminService {

    private static final Logger log = LoggerFactory.getLogger(BotAdminService.class);

    private final BotAdminRepository repository;

    /** 内存快照：小写邮箱集合。 */
    private volatile Set<String> adminEmails = Set.of();

    public BotAdminService(BotAdminRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        reload();
    }

    /** 全部自定义管理员（页面展示用）。 */
    public List<BotAdminEntity> list() {
        return repository.findAll();
    }

    /** 当前自定义管理员数量。 */
    public int count() {
        return adminEmails.size();
    }

    /** 该邮箱是否为自定义管理员（热路径，读内存）。 */
    public boolean isAdmin(String email) {
        return Emails.normalize(email).map(adminEmails::contains).orElse(false);
    }

    /** 新增一个自定义管理员；邮箱重复或格式非法时抛出业务异常。 */
    @Transactional
    public BotAdminEntity add(String email, String remark) {
        String normalized = Emails.normalize(email)
                .orElseThrow(() -> BusinessException.of("邮箱不能为空"));
        if (!Emails.isValid(normalized)) {
            throw BusinessException.of("邮箱格式不正确：" + email);
        }
        if (repository.findByEmail(normalized) != null) {
            throw BusinessException.of("该邮箱已经是自定义管理员：" + normalized);
        }

        BotAdminEntity entity = new BotAdminEntity();
        entity.setEmail(normalized);
        entity.setRemark(remark == null || remark.isBlank() ? null : remark.trim());
        repository.insert(entity);
        reload();
        log.info("新增自定义管理员: email={}", normalized);
        return repository.findByEmail(normalized);
    }

    /** 删除一个自定义管理员。 */
    @Transactional
    public void remove(Long id) {
        if (id == null) {
            throw BusinessException.of("缺少管理员 ID");
        }
        if (!repository.delete(id)) {
            throw BusinessException.of("管理员不存在或已被删除：" + id);
        }
        reload();
        log.info("已删除自定义管理员: id={}", id);
    }

    /** 从数据库重新加载内存快照。 */
    void reload() {
        try {
            Set<String> emails = new HashSet<>();
            for (BotAdminEntity entity : repository.findAll()) {
                Emails.normalize(entity.getEmail()).ifPresent(emails::add);
            }
            adminEmails = Set.copyOf(emails);
            log.info("自定义管理员已加载: count={}", adminEmails.size());
        } catch (Exception ex) {
            // 加载失败不能让机器人整体不可用：退化为「没有自定义管理员」，
            // Sub2API 自带的管理员仍然生效。
            log.warn("加载自定义管理员失败，暂时按空名单处理: {}", ex.getMessage());
            adminEmails = Set.of();
        }
    }
}