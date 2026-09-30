package com.monitor.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 监控平台单体应用启动类。
 *
 * <p>应用采用模块化单体结构：采集、上游适配、清洗和查询在同一进程内运行，
 * 但通过包边界和接口/事件控制依赖方向。当前阶段只启用调度和异步能力，
 * 后续模块实现后无需再调整启动入口。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}