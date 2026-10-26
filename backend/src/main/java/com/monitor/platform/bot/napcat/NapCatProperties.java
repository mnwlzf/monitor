package com.monitor.platform.bot.napcat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * NapCat WebUI 接入配置。
 *
 * <p>Monitor 与 NapCat 在同一个 compose 网络时，直接访问 {@code http://napcat:6099}；
 * WebUI token 从 NapCat 的 {@code webui.json} 读取，不写入 Monitor 的环境变量。</p>
 */
@ConfigurationProperties(prefix = "monitor.napcat")
public class NapCatProperties {

    /** 是否启用 NapCat 日志与扫码登录集成。 */
    private boolean enabled = true;

    /** NapCat WebUI 根地址。 */
    private String webuiBaseUrl = "http://napcat:6099";

    /** NapCat WebUI 配置文件路径（容器内挂载点）。 */
    private String webuiConfig = "/app/napcat-config/webui.json";

    /** 内存中保留的日志行数。 */
    private int logBufferSize = 2000;

    /** 单行日志最大字符数，超出部分截断。 */
    private int maxLogLineLength = 8192;

    /** 断线重连最大退避时间。 */
    private Duration reconnectMaxDelay = Duration.ofSeconds(30);

    /** WebUI 登录凭证缓存时长；失效时会自动重新登录。 */
    private Duration credentialTtl = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWebuiBaseUrl() { return webuiBaseUrl; }
    public void setWebuiBaseUrl(String webuiBaseUrl) { this.webuiBaseUrl = webuiBaseUrl; }
    public String getWebuiConfig() { return webuiConfig; }
    public void setWebuiConfig(String webuiConfig) { this.webuiConfig = webuiConfig; }
    public int getLogBufferSize() { return logBufferSize; }
    public void setLogBufferSize(int logBufferSize) { this.logBufferSize = logBufferSize; }
    public int getMaxLogLineLength() { return maxLogLineLength; }
    public void setMaxLogLineLength(int maxLogLineLength) { this.maxLogLineLength = maxLogLineLength; }
    public Duration getReconnectMaxDelay() { return reconnectMaxDelay; }
    public void setReconnectMaxDelay(Duration reconnectMaxDelay) { this.reconnectMaxDelay = reconnectMaxDelay; }
    public Duration getCredentialTtl() { return credentialTtl; }
    public void setCredentialTtl(Duration credentialTtl) { this.credentialTtl = credentialTtl; }
}
