package com.monitor.platform.bot;

import java.time.OffsetDateTime;

/** 一条机器人违禁词。 */
public class BotForbiddenWordEntity {

    private Long id;
    private String word;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
