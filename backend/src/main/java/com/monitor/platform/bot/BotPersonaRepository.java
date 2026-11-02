package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

import java.util.List;

/** 机器人人设持久化仓储。 */
@Repository
public class BotPersonaRepository {

    private final BotPersonaMapper mapper;

    public BotPersonaRepository(BotPersonaMapper mapper) {
        this.mapper = mapper;
    }

    public List<BotPersonaEntity> findAll() {
        return mapper.findAll();
    }

    public BotPersonaEntity findById(Long id) {
        return mapper.findById(id);
    }

    public BotPersonaEntity findDefault() {
        return mapper.findDefault();
    }

    public void insert(BotPersonaEntity entity) {
        mapper.insert(entity);
    }

    public void update(BotPersonaEntity entity) {
        mapper.update(entity);
    }

    public boolean delete(Long id) {
        return mapper.delete(id) > 0;
    }

    public void clearDefault() {
        mapper.clearDefault();
    }

    public void setDefault(Long id) {
        mapper.setDefault(id);
    }

    public int countGroupBindings(Long id) {
        return mapper.countGroupBindings(id);
    }
}
