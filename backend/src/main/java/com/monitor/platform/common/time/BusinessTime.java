package com.monitor.platform.common.time;

import java.time.ZoneId;

/**
 * 业务时区。
 *
 * <p>上游采集口径、报表、邮件告警、机器人统计统一按东八区计算。此前这些常量散落在十几个
 * 类里各自定义一份，改成集中一处，避免各写各的导致口径漂移。</p>
 */
public final class BusinessTime {

    /** 业务时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 业务时区 ID，用于需要字符串时区的场景（如上游接口的 {@code timezone} 参数）。 */
    public static final String ZONE_ID = "Asia/Shanghai";

    private BusinessTime() {
    }
}
