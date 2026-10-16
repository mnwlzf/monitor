package com.monitor.platform.notify;

import com.monitor.platform.mail.MailScene;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * QQ 消息接收人持久化仓储。
 */
@Repository
public class QqRecipientRepository {

    private final QqRecipientMapper qqRecipientMapper;

    public QqRecipientRepository(QqRecipientMapper qqRecipientMapper) {
        this.qqRecipientMapper = qqRecipientMapper;
    }

    /** 查询指定场景下的全部接收人。 */
    public List<QqRecipientEntity> findByScene(MailScene scene) {
        return qqRecipientMapper.selectByScene(scene.name());
    }

    /** 按场景、类型与目标 ID 查询，用于重复校验。 */
    public Optional<QqRecipientEntity> findBySceneAndTarget(MailScene scene, QqTargetType type, Long targetId) {
        return Optional.ofNullable(
                qqRecipientMapper.selectBySceneAndTarget(scene.name(), type.name(), targetId));
    }

    /** 新增接收人。 */
    public QqRecipientEntity save(QqRecipientEntity entity) {
        qqRecipientMapper.insert(entity);
        return entity;
    }

    /** 删除接收人。 */
    public void deleteById(Long id) {
        qqRecipientMapper.deleteById(id);
    }
}
