package com.monitor.platform.upstream;


import com.monitor.platform.collection.service.impl.Sub2CollectServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UpstreamAdapterContractTest {

    @Autowired
    private Sub2CollectServiceImpl service;

    @Test
    void tst1() {
        service.login("https://codex.trovebox.online","2696775653@qq.com");
        service.fetchProfile("https://codex.trovebox.online","2696775653@qq.com");
        service.fetchKeys("https://codex.trovebox.online","2696775653@qq.com");
    }
}
