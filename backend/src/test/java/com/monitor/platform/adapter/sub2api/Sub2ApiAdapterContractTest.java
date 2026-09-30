package com.monitor.platform.adapter.sub2api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sub2API 适配器联调测试。
 *
 * <p>该测试依赖真实 Sub2API 服务、Redis 和数据库环境，用于验证登录、个人信息、密钥列表和可用分组
 * 三个现有调用链路；普通单元测试不应依赖此用例。</p>
 */
@SpringBootTest
class Sub2ApiAdapterContractTest {

    private static final String BASE_URL = "https://codex.trovebox.online";
    private static final String EMAIL = "2696775653@qq.com";
    private static final String PASSWORD = "test";

    @Autowired
    private Sub2ApiAdapter sub2ApiAdapter;

    @Test
    void shouldLoginAndFetchSub2ApiData() {
        sub2ApiAdapter.login(BASE_URL, EMAIL, PASSWORD);
        sub2ApiAdapter.fetchProfile(BASE_URL, EMAIL, PASSWORD);
        sub2ApiAdapter.fetchKeys(BASE_URL, EMAIL, PASSWORD);
        sub2ApiAdapter.fetchAvailableGroups(BASE_URL, EMAIL, PASSWORD);
    }
}