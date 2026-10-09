package com.monitor.platform.pool.ingest;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAccountEntity;
import com.monitor.platform.pool.PoolSampleEntity;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 直连自建 Sub2API 只读库的增量采集服务。
 *
 * <p>与「走 admin 接口」的 {@code pool-samples} 互补：</p>
 * <ul>
 *     <li>本服务按 {@code usage_logs.id} 做真增量（主键范围扫描），每轮只拉新增行，
 *         毫秒级返回，可支撑 10~30 秒轮询，把数据滞后压到秒级；</li>
 *     <li>{@code pool-samples} 仍按 10 分钟走 admin 接口，负责号池账号列表、健康状态与历史回补。</li>
 * </ul>
 *
 * <p>未配置直连只读库（{@code monitor.pool.ingest.*}）时，每轮直接跳过。</p>
 */
@Service
public class PoolDbIngestService {

    private static final Logger log = LoggerFactory.getLogger(PoolDbIngestService.class);

    private final PoolIngestProperties properties;
    private final Sub2ApiUsageLogReader reader;
    private final Sub2ApiIngestCursorRepository cursorRepository;
    private final PlatformRepository platformRepository;
    private final PoolAccountRepository poolAccountRepository;
    private final PoolSampleRepository poolSampleRepository;
    /** 上次失败是否已提示过：高频任务（30 秒）避免同一错误刷屏。 */
    private volatile boolean failureLogged = false;

    public PoolDbIngestService(PoolIngestProperties properties,
                               Sub2ApiUsageLogReader reader,
                               Sub2ApiIngestCursorRepository cursorRepository,
                               PlatformRepository platformRepository,
                               PoolAccountRepository poolAccountRepository,
                               PoolSampleRepository poolSampleRepository) {
        this.properties = properties;
        this.reader = reader;
        this.cursorRepository = cursorRepository;
        this.platformRepository = platformRepository;
        this.poolAccountRepository = poolAccountRepository;
        this.poolSampleRepository = poolSampleRepository;
    }

    /**
     * 执行一轮增量同步。
     *
     * <p>每批写入成功后就推进游标，因此中途异常也不会重复拉取（且 {@code request_id} 仍然幂等）。</p>
     */
    public void ingestOnce() {
        if (!properties.isConfigured() || !reader.isAvailable()) {
            log.debug("未配置 Sub2API 直连只读库，跳过直连增量采集");
            return;
        }
        try {
            doIngest();
            failureLogged = false;
        } catch (Exception ex) {
            // 这条链路是可选的高频补充通道：失败只提示一次，避免每 30 秒刷一次堆栈。
            // 数据不会丢——游标未推进，下一轮会重新拉取。
            if (!failureLogged) {
                failureLogged = true;
                log.warn("直连库增量采集失败（相同错误不再重复提示，恢复后会自动继续）: {}", ex.getMessage());
            }
        }
    }

    private void doIngest() {
        PlatformEntity platform = resolvePoolPlatform();
        if (platform == null) {
            log.debug("没有启用中的号池监控源，跳过直连增量采集");
            return;
        }

        List<Long> accountIds = poolAccountRepository.findByPlatform(platform.getId()).stream()
                .map(PoolAccountEntity::getExternalAccountId)
                .filter(Objects::nonNull)
                .toList();
        if (accountIds.isEmpty()) {
            return;
        }

        long cursor = cursorRepository.lastUsageLogId();
        if (cursor <= 0) {
            // 首次启用：从当前最大 id 开始，避免一次性回灌全部历史（历史交给 admin 接口任务）
            long maxId = reader.currentMaxId();
            cursorRepository.save(maxId);
            log.info("直连库增量采集已初始化游标: usage_log_id={}（从当前开始，不回灌历史）", maxId);
            return;
        }

        int inserted = 0;
        int batches = 0;
        long advancedTo = cursor;
        int maxBatches = Math.max(1, properties.getMaxBatchesPerRun());
        while (batches < maxBatches) {
            List<Sub2ApiUsageLogRow> rows = reader.fetchAfter(advancedTo, accountIds, properties.getBatchSize());
            if (rows.isEmpty()) {
                break;
            }
            List<PoolSampleEntity> entities = new ArrayList<>(rows.size());
            for (Sub2ApiUsageLogRow row : rows) {
                if (row.requestId() != null && row.createdAt() != null) {
                    entities.add(toEntity(platform.getId(), row));
                }
            }
            inserted += poolSampleRepository.insertBatch(entities);

            advancedTo = rows.get(rows.size() - 1).id();
            cursorRepository.save(advancedTo);
            batches++;
            if (rows.size() < properties.getBatchSize()) {
                break;
            }
        }

        if (inserted > 0) {
            log.info("直连库增量采集完成: platformId={}, batches={}, insertedSamples={}, cursor={}",
                    platform.getId(), batches, inserted, advancedTo);
        } else if (batches >= maxBatches) {
            log.warn("直连库增量采集达到单轮批数上限，剩余数据留到下一轮: platformId={}, cursor={}",
                    platform.getId(), advancedTo);
        }
    }

    /** 取「号池监控源」平台；只允许一个（多个时取 id 最小的并告警）。 */
    private PlatformEntity resolvePoolPlatform() {
        List<PlatformEntity> sources = platformRepository.findAll().stream()
                .filter(item -> Boolean.TRUE.equals(item.getPoolMonitoringEnabled()))
                .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                .toList();
        if (sources.isEmpty()) {
            return null;
        }
        if (sources.size() > 1) {
            log.warn("检测到多个启用的号池监控源，直连库增量采集仅同步到第一个: {}", sources.get(0).getPlatformName());
        }
        return sources.get(0);
    }

    private PoolSampleEntity toEntity(Integer platformId, Sub2ApiUsageLogRow row) {
        PoolSampleEntity entity = new PoolSampleEntity();
        entity.setPlatformId(platformId);
        entity.setExternalAccountId(row.accountId());
        entity.setRequestId(row.requestId());
        entity.setApiKeyId(row.apiKeyId());
        entity.setModel(row.model());
        entity.setCreatedAt(row.createdAt());
        entity.setFirstTokenMs(row.firstTokenMs());
        entity.setDurationMs(row.durationMs());
        entity.setInputTokens(row.inputTokens());
        entity.setOutputTokens(row.outputTokens());
        entity.setCacheReadTokens(row.cacheReadTokens());
        entity.setCacheCreationTokens(row.cacheCreationTokens());
        entity.setTotalCost(row.totalCost());
        entity.setActualCost(row.actualCost());
        entity.setIngestedAt(OffsetDateTime.now());
        return entity;
    }
}