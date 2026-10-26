package com.monitor.platform.bot.napcat;

import com.monitor.platform.api.dto.NapCatLogLineResponse;
import com.monitor.platform.api.dto.NapCatLogsResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 保持 NapCat SSE 日志流，并在内存里保留最近若干行供页面查看。
 *
 * <p><strong>IO 约束：</strong>日志只流式读取，不落盘；内存缓冲区固定上限，
 * 单行超长截断；断线重连使用指数退避，页面接口最多返回 1000 行。</p>
 */
@Service
public class NapCatLogCollector {

    private static final Logger log = LoggerFactory.getLogger(NapCatLogCollector.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long INITIAL_RETRY_MILLIS = 3_000L;

    private final NapCatProperties properties;
    private final NapCatWebUiClient client;
    private final Deque<NapCatLogLineResponse> lines = new ArrayDeque<>();

    private volatile boolean running = true;
    private volatile boolean connected;
    private volatile String lastError = "";
    private volatile Instant updatedAt;
    private Thread worker;

    public NapCatLogCollector(NapCatProperties properties, NapCatWebUiClient client) {
        this.properties = properties;
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        worker = Thread.ofVirtual().name("napcat-log-collector").start(this::runLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 当前日志快照；limit 限制在 1~1000，响应大小固定有上限。 */
    public NapCatLogsResponse snapshot(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<NapCatLogLineResponse> result;
        synchronized (lines) {
            int skip = Math.max(0, lines.size() - safeLimit);
            result = new ArrayList<>(lines).subList(skip, lines.size());
        }
        return new NapCatLogsResponse(client.isConfigured(), connected, result, updatedAt, lastError);
    }

    private void runLoop() {
        long retryMillis = INITIAL_RETRY_MILLIS;
        long maxRetryMillis = properties.getReconnectMaxDelay() == null
                ? 30_000L : Math.max(1_000L, properties.getReconnectMaxDelay().toMillis());
        while (running) {
            if (!client.isConfigured()) {
                lastError = "未找到 NapCat WebUI 配置";
                sleep(10_000L);
                continue;
            }
            try {
                lastError = "";
                connected = true;
                retryMillis = INITIAL_RETRY_MILLIS;
                client.streamLogs(this::append);
            } catch (RuntimeException ex) {
                lastError = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 500);
                client.invalidateCredential();
                log.debug("NapCat 日志流断开，将重连: {}", lastError);
                sleep(retryMillis);
                retryMillis = Math.min(maxRetryMillis, retryMillis * 2);
            } finally {
                connected = false;
            }
            if (running) {
                sleep(INITIAL_RETRY_MILLIS);
            }
        }
    }

    private void append(NapCatWebUiClient.LogEntry entry) {
        String message = truncate(sanitize(entry.message()), Math.max(256, properties.getMaxLogLineLength()));
        NapCatLogLineResponse line = new NapCatLogLineResponse(
                TIME.format(OffsetDateTime.now(ZONE)), entry.level(), message);
        synchronized (lines) {
            int max = Math.max(100, Math.min(properties.getLogBufferSize(), 5000));
            while (lines.size() >= max) {
                lines.removeFirst();
            }
            lines.addLast(line);
            updatedAt = Instant.now();
        }
    }

    /** 日志里可能出现 WebUI token 或带 token 的 URL，页面展示前统一打码。 */
    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("(?i)(WebUi Token:\\s*)\\S+", "$1***")
                .replaceAll("(?i)(token=)[^&\\s]+", "$1***");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "…（已截断）";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}