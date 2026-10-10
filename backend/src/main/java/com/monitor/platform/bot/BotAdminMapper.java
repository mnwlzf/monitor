package com.monitor.platform.bot;

import java.util.List;

/**
 * 自定义管理员 Mapper。
 *
 * <p>邮箱一律按 {@code lower(email)} 比对，与唯一索引保持一致，避免大小写造成重复。</p>
 */
public interface BotAdminMapper {

    List<BotAdminEntity> selectAll();

    BotAdminEntity selectByEmail(String email);

    int insertAdmin(BotAdminEntity entity);

    int deleteById(Long id);
}