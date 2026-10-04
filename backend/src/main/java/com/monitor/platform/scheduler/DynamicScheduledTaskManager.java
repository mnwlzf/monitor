package com.monitor.platform.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态定时任务调度管理器。
 *
 * <p>启动时读取数据库中的启用任务，并提交到统一调度线程池；页面增删改任务或切换启用状态后，
 * 调用 {@link #reload()} 即可重新加载调度，无需重启应用。</p>
 */
@Component
public class DynamicScheduledTaskManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamicScheduledTaskManager.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final Map<String, ScheduledTaskHandler> handlerMap;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();

    public DynamicScheduledTaskManager(ThreadPoolTaskScheduler taskScheduler,
                                       ScheduledTaskRepository scheduledTaskRepository,
                                       List<ScheduledTaskHandler> handlers) {
        this.taskScheduler = taskScheduler;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.handlerMap = new ConcurrentHashMap<>();
        for (ScheduledTaskHandler handler : handlers) {
            handlerMap.put(handler.code(), handler);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /**
     * 重新加载所有启用任务，先取消旧调度再注册新调度。
     */
    public synchronized void reload() {
        cancelAll();
        List<ScheduledTaskEntity> tasks = scheduledTaskRepository.findEnabled();
        for (ScheduledTaskEntity task : tasks) {
            scheduleTask(task);
        }
        log.info("动态定时任务加载完成: enabledTaskCount={}", tasks.size());
    }

    /** 查询页面可选的任务处理器。 */
    public List<ScheduledTaskHandler> listHandlers() {
        return handlerMap.values().stream().toList();
    }

    /** 是否存在指定编码的处理器。 */
    public boolean containsHandler(String taskCode) {
        return handlerMap.containsKey(taskCode);
    }

    /** 立即触发一次任务，使用调度线程池异步执行。 */
    public void triggerNow(Long taskId) {
        taskScheduler.execute(() -> executeTask(taskId));
    }

    private void scheduleTask(ScheduledTaskEntity task) {
        ScheduledTaskHandler handler = handlerMap.get(task.getTaskCode());
        if (handler == null) {
            log.warn("跳过未注册处理器的定时任务: taskId={}, taskCode={}", task.getId(), task.getTaskCode());
            return;
        }
        try {
            CronExpression.parse(task.getCronExpression());
            CronTrigger trigger = new CronTrigger(task.getCronExpression(), ZoneId.of(task.getTimezone()));
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTask(task.getId()), trigger);
            futures.put(task.getId(), future);
            log.info("定时任务已注册: taskId={}, taskName={}, cron={}, timezone={}",
                    task.getId(), task.getTaskName(), task.getCronExpression(), task.getTimezone());
        } catch (Exception ex) {
            log.error("定时任务注册失败: taskId={}, taskName={}, cron={}",
                    task.getId(), task.getTaskName(), task.getCronExpression(), ex);
        }
    }

    private void cancelAll() {
        for (ScheduledFuture<?> future : futures.values()) {
            future.cancel(false);
        }
        futures.clear();
    }

    private void executeTask(Long taskId) {
        if (!runningTaskIds.add(taskId)) {
            log.warn("定时任务正在执行，跳过本次触发: taskId={}", taskId);
            return;
        }
        try {
            ScheduledTaskEntity task = scheduledTaskRepository.findById(taskId).orElse(null);
            if (task == null || !Boolean.TRUE.equals(task.getEnabled())) {
                return;
            }
            ScheduledTaskHandler handler = handlerMap.get(task.getTaskCode());
            if (handler == null) {
                log.warn("定时任务处理器不存在: taskId={}, taskCode={}", taskId, task.getTaskCode());
                return;
            }

            scheduledTaskRepository.updateRunStatus(taskId, java.time.OffsetDateTime.now(), "RUNNING", null);
            log.info("定时任务开始执行: taskId={}, taskName={}", taskId, task.getTaskName());
            long started = System.currentTimeMillis();
            try {
                handler.execute();
                scheduledTaskRepository.updateRunStatus(taskId, java.time.OffsetDateTime.now(), "SUCCESS", null);
                log.info("定时任务执行成功: taskId={}, taskName={}, durationMs={}",
                        taskId, task.getTaskName(), System.currentTimeMillis() - started);
            } catch (Exception ex) {
                scheduledTaskRepository.updateRunStatus(taskId, java.time.OffsetDateTime.now(), "FAILED",
                        truncate(ex.getMessage(), 500));
                log.error("定时任务执行失败: taskId={}, taskName={}", taskId, task.getTaskName(), ex);
            }
        } finally {
            runningTaskIds.remove(taskId);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
