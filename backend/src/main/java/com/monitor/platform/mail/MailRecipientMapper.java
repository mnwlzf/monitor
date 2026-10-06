package com.monitor.platform.mail;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 邮件收件人 Mapper。
 */
public interface MailRecipientMapper {

    List<MailRecipientEntity> selectAll();

    MailRecipientEntity selectByEmail(@Param("email") String email);

    int insert(MailRecipientEntity entity);

    int deleteById(@Param("id") Long id);
}