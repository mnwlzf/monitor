package com.monitor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录与权限配置。
 *
 * <p>用户列表来自配置文件或环境变量，密码支持明文（启动时用 BCrypt 加密）或
 * {@code {bcrypt}} 前缀的哈希值。角色目前支持 {@code ADMIN}（读写）与
 * {@code VIEWER}（只读）。</p>
 */
@ConfigurationProperties(prefix = "monitor.security")
public class SecurityProperties {

    /** 是否启用登录鉴权，默认开启。 */
    private boolean enabled = true;

    /** 会话超时时间，例如 8h、30m。 */
    private String sessionTimeout = "8h";

    /** 允许登录的用户列表。 */
    private List<User> users = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(String sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    /**
     * 单个登录用户。
     */
    public static class User {

        /** 登录名。 */
        private String username;

        /** 密码，明文或 {bcrypt} 哈希。 */
        private String password;

        /** 角色列表，例如 ADMIN、VIEWER。 */
        private List<String> roles = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}