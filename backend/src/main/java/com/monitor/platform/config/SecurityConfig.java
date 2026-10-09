package com.monitor.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web 安全配置。
 *
 * <p>系统采用会话（Session）登录：静态页面与登录接口匿名开放，其余接口必须登录。
 * 权限分两级：{@code ADMIN} 可读写，{@code VIEWER} 只读。</p>
 *
 * <p>用户来自 {@code monitor.security.users} 配置，可通过环境变量
 * {@code MONITOR_ADMIN_USERNAME} / {@code MONITOR_ADMIN_PASSWORD} 注入。
 * 若未配置任何用户，则生成一个随机密码的管理员并在启动日志中打印。</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(SecurityProperties securityProperties, ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    /** 密码编码器，统一使用 BCrypt。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 从配置构建用户信息。未配置任何用户时生成随机管理员密码并打印到日志。
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        List<UserDetails> users = new ArrayList<>();
        if (securityProperties.getUsers() != null) {
            for (SecurityProperties.User user : securityProperties.getUsers()) {
                if (!StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
                    continue;
                }
                List<String> roles = (user.getRoles() == null || user.getRoles().isEmpty())
                        ? List.of("ADMIN")
                        : user.getRoles();
                users.add(User.withUsername(user.getUsername())
                        .password(encodePassword(user.getPassword(), passwordEncoder))
                        .roles(roles.toArray(String[]::new))
                        .build());
            }
        }

        if (users.isEmpty()) {
            String generated = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            users.add(User.withUsername("admin")
                    .password(passwordEncoder.encode(generated))
                    .roles("ADMIN")
                    .build());
            log.warn("========================================================");
            log.warn("未配置 monitor.security.users，已生成临时管理员账号");
            log.warn("  用户名: admin");
            log.warn("  密码  : {}", generated);
            log.warn("请通过 MONITOR_ADMIN_USERNAME / MONITOR_ADMIN_PASSWORD 配置固定账号");
            log.warn("========================================================");
        }

        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 定义 HTTP 安全过滤链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!securityProperties.isEnabled()) {
            log.warn("monitor.security.enabled=false，已关闭登录鉴权，仅建议本地开发使用");
            http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        http
                // SPA 使用同源请求 + SameSite=Strict Cookie，这里关闭 CSRF Token 以简化前端
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // 前端静态资源与登录接口匿名开放
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.svg", "/robots.txt", "/assets/**", "/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        // 系统设置（含 SMTP 等敏感配置）：仅 ADMIN
                        .requestMatchers("/api/v1/settings/**").hasRole("ADMIN")
                        // 读接口：ADMIN 与 VIEWER 都可访问
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("ADMIN", "VIEWER")
                        // 写接口：仅 ADMIN
                        .requestMatchers("/api/v1/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden)
                );

        return http.build();
    }

    private String encodePassword(String raw, PasswordEncoder encoder) {
        return raw.startsWith("{bcrypt}") || raw.startsWith("$2a$") || raw.startsWith("$2b$") || raw.startsWith("$2y$")
                ? raw
                : encoder.encode(raw);
    }

    /** 未登录访问受保护资源时返回统一 JSON 401。 */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException ex) throws IOException {
        writeJson(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或登录已过期");
    }

    /** 已登录但权限不足时返回统一 JSON 403。 */
    private void writeForbidden(HttpServletRequest request, HttpServletResponse response,
                                org.springframework.security.access.AccessDeniedException ex) throws IOException {
        writeJson(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限执行该操作");
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse body = new ErrorResponse(code, message, null, Instant.now(), Map.of());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}