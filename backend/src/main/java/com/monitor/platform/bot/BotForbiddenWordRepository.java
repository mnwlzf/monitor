package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

import java.util.List;

/** 机器人违禁词持久化仓储。 */
@Repository
public class BotForbiddenWordRepository {

    private final BotForbiddenWordMapper mapper;

    public BotForbiddenWordRepository(BotForbiddenWordMapper mapper) {
        this.mapper = mapper;
    }

    public List<BotForbiddenWordEntity> findAll() {
        return mapper.selectAll();
    }

    /** 按词查询；数据库侧使用 lower(word) 做大小写不敏感匹配。 */
    public BotForbiddenWordEntity findByWord(String word) {
        return mapper.selectByWord(word);
    }

    public void insert(BotForbiddenWordEntity entity) {
        mapper.insertWord(entity);
    }

    public boolean delete(Long id) {
        return mapper.deleteById(id) > 0;
    }
}
