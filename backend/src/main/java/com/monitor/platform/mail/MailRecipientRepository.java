package com.monitor.platform.mail;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 邮件收件人持久化仓储。
 */
@Repository
public class MailRecipientRepository {

    private final MailRecipientMapper mailRecipientMapper;

    public MailRecipientRepository(MailRecipientMapper mailRecipientMapper) {
        this.mailRecipientMapper = mailRecipientMapper;
    }

    /** 查询全部收件人。 */
    public List<MailRecipientEntity> findAll() {
        return mailRecipientMapper.selectAll();
    }

    /** 按邮箱查询，用于重复校验（忽略大小写）。 */
    public Optional<MailRecipientEntity> findByEmail(String email) {
        return Optional.ofNullable(mailRecipientMapper.selectByEmail(email));
    }

    /** 新增收件人。 */
    public MailRecipientEntity save(MailRecipientEntity entity) {
        mailRecipientMapper.insert(entity);
        return entity;
    }

    /** 删除收件人。 */
    public void deleteById(Long id) {
        mailRecipientMapper.deleteById(id);
    }
}