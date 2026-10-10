package com.monitor.platform.bot.identity;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * Sub2API 平台用户同步任务处理器。
 *
 * <p>把平台用户的邮箱与角色刷新到 Redis，供 QQ 机器人识别身份。
 * 在「定时任务」页面可调整执行频率（默认 10 分钟一次）。</p>
 */
@Component
public class Sub2ApiUserSyncTaskHandler implements ScheduledTaskHandler {

    private final Sub2ApiUserDirectory directory;

    public Sub2ApiUserSyncTaskHandler(Sub2ApiUserDirectory directory) {
        this.directory = directory;
    }

    @Override
    public String code() {
        return "sub2api-user-sync";
    }

    @Override
    public String name() {
        return "Sub2API 用户同步";
    }

    @Override
    public String description() {
        return "同步自建 Sub2API 的平台用户邮箱与角色到 Redis，供 QQ 机器人识别身份";
    }

    @Override
    public void execute() {
        directory.sync();
    }
}