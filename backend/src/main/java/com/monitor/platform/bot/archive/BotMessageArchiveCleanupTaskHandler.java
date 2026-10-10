package com.monitor.platform.bot.archive;

import com.monitor.platform.common.schedule.ScheduledTaskHandler;
import org.springframework.stereotype.Component;

/**
 * 消息存档清理任务。
 *
 * <p>存档会持续增长，按 {@code monitor.bot.archive.retention-days} 保留天数清理旧记录。
 * 保留天数设为 0 或负数时本任务不删任何东西（相当于永久保留）。</p>
 */
@Component
public class BotMessageArchiveCleanupTaskHandler implements ScheduledTaskHandler {

    private final BotMessageArchiveService archiveService;

    public BotMessageArchiveCleanupTaskHandler(BotMessageArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Override
    public String code() {
        return "bot-archive-cleanup";
    }

    @Override
    public String name() {
        return "机器人消息存档清理";
    }

    @Override
    public String description() {
        return "按保留天数清理机器人消息存档；保留天数非正数时自动跳过";
    }

    @Override
    public void execute() {
        archiveService.cleanup();
    }
}