package com.monitor.platform.mail;

import java.time.OffsetDateTime;

/**
 * 页面可配置的 SMTP 邮件设置实体（全局单行）。
 *
 * <p>密码以 AES-GCM 密文形式分列存储，实体本身不持有明文。</p>
 */
public class MailSettingsEntity {

    private Integer id;
    private Boolean enabled;
    private String host;
    private Integer port;
    private String username;
    private String passwordEncryptionAlgorithm;
    private String passwordEncryptedPayload;
    private String passwordInitializationVector;
    private Integer passwordKeyVersion;
    private String fromAddress;
    private String fromName;
    private Boolean useTls;
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordEncryptionAlgorithm() { return passwordEncryptionAlgorithm; }
    public void setPasswordEncryptionAlgorithm(String passwordEncryptionAlgorithm) { this.passwordEncryptionAlgorithm = passwordEncryptionAlgorithm; }
    public String getPasswordEncryptedPayload() { return passwordEncryptedPayload; }
    public void setPasswordEncryptedPayload(String passwordEncryptedPayload) { this.passwordEncryptedPayload = passwordEncryptedPayload; }
    public String getPasswordInitializationVector() { return passwordInitializationVector; }
    public void setPasswordInitializationVector(String passwordInitializationVector) { this.passwordInitializationVector = passwordInitializationVector; }
    public Integer getPasswordKeyVersion() { return passwordKeyVersion; }
    public void setPasswordKeyVersion(Integer passwordKeyVersion) { this.passwordKeyVersion = passwordKeyVersion; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public Boolean getUseTls() { return useTls; }
    public void setUseTls(Boolean useTls) { this.useTls = useTls; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 是否已保存过 SMTP 密码。 */
    public boolean hasPassword() {
        return passwordEncryptedPayload != null && !passwordEncryptedPayload.isBlank();
    }
}