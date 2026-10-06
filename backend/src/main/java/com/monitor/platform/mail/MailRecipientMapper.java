package com.monitor.platform.mail;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 邮件收件人 Mapper。
 */
public interface MailRecipientMapper {

    /** 查询指定场景下的全部收件人。 */
    List<MailRecipientEntity> selectByScene(@Param("scene") String scene);

    /** 按场景与邮箱查询，用于重复校验。 */
    MailRecipientEntity selectBySceneAndEmail(@Param("scene") String scene, @Param("email") String email);

    int insert(MailRecipientEntity entity);

    int deleteById(@Param("id") Long id);
}