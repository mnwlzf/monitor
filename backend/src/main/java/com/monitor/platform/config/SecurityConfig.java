package com.monitor.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Web 安全配置。
 *
 * <p>当前阶段先开放上游管理接口用于本地开发和前端联调，后续接入管理端认证后
 * 再收紧为登录用户访问。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 定义 HTTP 安全过滤链。上游管理接口和健康检查当前匿名开放，其余请求要求认证。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/upstream/**", "/api/v1/scheduled-tasks/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}