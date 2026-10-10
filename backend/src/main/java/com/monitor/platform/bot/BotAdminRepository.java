package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 自定义管理员持久化仓储。
 */
@Repository
public class BotAdminRepository {

    private final BotAdminMapper botAdminMapper;

    public BotAdminRepository(BotAdminMapper botAdminMapper) {
        this.botAdminMapper = botAdminMapper;
    }

    /** 全部自定义管理员，按添加时间排序。 */
    public List<BotAdminEntity> findAll() {
        return botAdminMapper.selectAll();
    }

    /** 按邮箱查（大小写不敏感）。 */
    public BotAdminEntity findByEmail(String email) {
        return botAdminMapper.selectByEmail(email);
    }

    /** 新增，主键回填到实体。 */
    public void insert(BotAdminEntity entity) {
        botAdminMapper.insertAdmin(entity);
    }

    /** 按主键删除，返回是否真的删掉了一行。 */
    public boolean delete(Long id) {
        return botAdminMapper.deleteById(id) > 0;
    }
}