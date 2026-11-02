package com.monitor.platform.bot;

import java.time.OffsetDateTime;

/** 页面维护的机器人人设。 */
public class BotPersonaEntity {

    private Long id;
    private String name;
    private String description;
    private String characterPrompt;
    private String stylePrompt;
    private Boolean enabled;
    private Boolean defaultPersona;
    private Integer version;
    private Integer boundGroupCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCharacterPrompt() { return characterPrompt; }
    public void setCharacterPrompt(String characterPrompt) { this.characterPrompt = characterPrompt; }
    public String getStylePrompt() { return stylePrompt; }
    public void setStylePrompt(String stylePrompt) { this.stylePrompt = stylePrompt; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getDefaultPersona() { return defaultPersona; }
    public void setDefaultPersona(Boolean defaultPersona) { this.defaultPersona = defaultPersona; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getBoundGroupCount() { return boundGroupCount; }
    public void setBoundGroupCount(Integer boundGroupCount) { this.boundGroupCount = boundGroupCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
