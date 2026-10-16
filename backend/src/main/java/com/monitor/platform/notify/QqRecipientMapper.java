package com.monitor.platform.notify;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * QQ 消息接收人 Mapper。
 */
public interface QqRecipientMapper {

    /** 查询指定场景下的全部接收人。 */
    List<QqRecipientEntity> selectByScene(@Param("scene") String scene);

    /** 按场景 + 类型 + 目标 ID 查询，用于重复校验。 */
    QqRecipientEntity selectBySceneAndTarget(@Param("scene") String scene,
                                             @Param("targetType") String targetType,
                                             @Param("targetId") Long targetId);

    int insert(QqRecipientEntity entity);

    int deleteById(@Param("id") Long id);
}
