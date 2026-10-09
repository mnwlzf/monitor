package com.monitor.platform.pool;

import com.monitor.platform.api.dto.PoolAccountResponse;
import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.api.dto.PoolModelMetricsResponse;
import com.monitor.platform.api.dto.PoolSeriesPointResponse;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.pool.ingest.PoolIngestProperties;
import com.monitor.platform.pool.ingest.Sub2ApiIngestCursorRepository;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 号池监控查询服务。
 *
 * <p>返回号池账号的健康状态、聚合指标、时序曲线与按模型明细。
 * 「样本量「是否足够」的判断交给前端：这里始终返回真实聚合结果与样本数，
 * 不做任何数据裁剪或补零，避免用 0 掩盖「样本不足」。</p>
 */
@Service
public class PoolQueryService {

    private static final int CACHE_RATE_SCALE = 4;
    /** 业务时区：与 Mapper 里的 date_trunc 口径保持一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PoolAccountRepository poolAccountRepository;
    private final PoolSampleRepository poolSampleRepository;
    private final AccountApiKeyRepository apiKeyRepository;
    private final Sub2ApiIngestCursorRepository ingestCursorRepository;
    private final PoolIngestProperties ingestProperties;

    public PoolQueryService(PoolAccountRepository poolAccountRepository,
                            PoolSampleRepository poolSampleRepository,
                            AccountApiKeyRepository apiKeyRepository,
                            Sub2ApiIngestCursorRepository ingestCursorRepository,
                            PoolIngestProperties ingestProperties) {
        this.poolAccountRepository = poolAccountRepository;
        this.poolSampleRepository = poolSampleRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.ingestCursorRepository = ingestCursorRepository;
        this.ingestProperties = ingestProperties;
    }

    /**
     * 平台下出现过的模型名，供色块矩阵的「模型」筛选使用。
     *
     * <p>窗口取得比页面时间维度更宽（最少 30 天），避免切换短窗口时筛选项跳来跳去。</p>
     */
    public List<String> modelOptions(Integer platformId, String range) {
        OffsetDateTime to = OffsetDateTime.now();
        Duration window = resolveRange(range);
        if (window.compareTo(Duration.ofDays(30)) < 0) {
            window = Duration.ofDays(30);
        }
        return poolSampleRepository.modelOptions(platformId, to.minus(window), to);
    }

    /**
     * 直连库增量采集的运行状态。
     *
     * <p>用来回答「分钟级缓存率为什么是空的」：直连库是否配置完整、游标推到哪、
     * 本地最新一条明细有多旧。全部读本地表，不碰只读上游，接口本身很轻。</p>
     */
    public PoolIngestStatusResponse ingestStatus(Integer platformId) {
        OffsetDateTime latestSampleAt = poolSampleRepository.findLatestCreatedAt(platformId);
        OffsetDateTime now = OffsetDateTime.now();
        Long lagSeconds = latestSampleAt == null ? null
                : Math.max(0L, Duration.between(latestSampleAt, now).getSeconds());
        return new PoolIngestStatusResponse(
                ingestProperties.isEnabled(),
                ingestProperties.isConfigured(),
                ingestProperties.getPassword() != null && !ingestProperties.getPassword().isBlank(),
                ingestCursorRepository.lastUsageLogId(),
                ingestCursorRepository.lastRunAt(),
                latestSampleAt,
                lagSeconds);
    }

    /**
     * 查询平台下号池账号列表及其时间窗聚合指标。
     */
    public List<PoolAccountResponse> listAccounts(Integer platformId, String range) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(resolveRange(range));

        Map<Long, PoolAccountMetrics> metricsByAccount = new HashMap<>();
        for (PoolAccountMetrics metrics : poolSampleRepository.aggregateByPlatform(platformId, from, to)) {
            if (metrics.getExternalAccountId() != null) {
                metricsByAccount.put(metrics.getExternalAccountId(), metrics);
            }
        }

        List<PoolAccountResponse> result = new ArrayList<>();
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            PoolAccountMetrics metrics = metricsByAccount.get(account.getExternalAccountId());
            result.add(toResponse(account, metrics));
        }
        return result;
    }

    /**
     * 号池色块矩阵（热力图）趋势。
     *
     * <p>行 = 平台 / 号池账号，列 = 等宽时间桶。每格返回该账号在该时间段内的聚合指标；
     * 没有样本的格子 requests=0、比率为 null，交给前端画成「无流量」灰色，不用 0 冒充。</p>
     *
     * @param range              时间维度：90m / 6h / 12h / 1d（24h）/ 7d / 30d
     * @param granularity        聚合粒度：minute / hour / day，缺省按 range 推断
     * @param models             模型过滤（空表示全部）
     * @param externalAccountIds 号池账号过滤（空表示全部）
     * @param platforms          账号上游平台过滤（openai / anthropic ...，空表示全部）
     */
    public PoolHeatmapResponse heatmap(Integer platformId, String range, String granularity,
                                       Collection<String> models, Collection<Long> externalAccountIds,
                                       Collection<String> platforms) {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(resolveRange(range));
        String unit = resolveGranularity(granularity, range);
        int stepMinutes = stepMinutes(unit);

        List<OffsetDateTime> buckets = buildBuckets(from, to, unit);
        Map<Long, Integer> indexByBucket = new HashMap<>();
        for (int i = 0; i < buckets.size(); i++) {
            indexByBucket.put(buckets.get(i).toInstant().toEpochMilli(), i);
        }

        Set<String> modelFilter = normalizeModels(models);
        Set<String> platformFilter = normalizeModels(platforms);
        Set<Long> accountFilter = new LinkedHashSet<>();
        if (externalAccountIds != null) {
            for (Long id : externalAccountIds) {
                if (id != null) {
                    accountFilter.add(id);
                }
            }
        }

        Map<Long, HeatmapAccumulator[]> cellAcc = new HashMap<>();
        Map<Long, HeatmapAccumulator> totalAcc = new HashMap<>();
        HeatmapAccumulator summaryAcc = new HeatmapAccumulator();
        for (PoolHeatmapBucket row : poolSampleRepository.heatmapBuckets(
                platformId, from, to, unit, modelFilter, accountFilter)) {
            if (row.getExternalAccountId() == null || row.getBucket() == null) {
                continue;
            }
            Integer index = indexByBucket.get(row.getBucket().toInstant().toEpochMilli());
            if (index == null) {
                continue;
            }
            cellAcc.computeIfAbsent(row.getExternalAccountId(), key -> newAccumulators(buckets.size()))[index].add(row);
            totalAcc.computeIfAbsent(row.getExternalAccountId(), key -> new HeatmapAccumulator()).add(row);
        }

        List<PoolHeatmapResponse.Row> rows = new ArrayList<>();
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            if (!accountFilter.isEmpty() && !accountFilter.contains(account.getExternalAccountId())) {
                continue;
            }
            if (!platformFilter.isEmpty() && !platformFilter.contains(account.getPlatform())) {
                continue;
            }
            HeatmapAccumulator[] cells = cellAcc.get(account.getExternalAccountId());
            HeatmapAccumulator total = totalAcc.get(account.getExternalAccountId());
            if (total != null) {
                summaryAcc.merge(total);
            }
            List<PoolHeatmapResponse.Metrics> cellMetrics = new ArrayList<>(buckets.size());
            for (int i = 0; i < buckets.size(); i++) {
                cellMetrics.add(cells == null ? emptyMetrics(stepMinutes) : cells[i].toMetrics(stepMinutes));
            }
            AccountApiKeyEntity key = findBoundKey(account.getBoundKeyId());
            rows.add(new PoolHeatmapResponse.Row(
                    account.getExternalAccountId(),
                    account.getName(),
                    account.getPlatform(),
                    key == null ? null : key.getKeyName(),
                    key == null ? null : key.getKeyMasked(),
                    total == null ? emptyMetrics(stepMinutes) : total.toMetrics(stepMinutes),
                    cellMetrics));
        }
        // 有流量的账号排在前面，避免一堆零请求账号把有数据的挤下去
        rows.sort(Comparator.comparingLong((PoolHeatmapResponse.Row row) -> row.total().requests()).reversed()
                .thenComparing(row -> row.platform() == null ? "" : row.platform())
                .thenComparing(row -> row.name() == null ? "" : row.name()));

        return new PoolHeatmapResponse(from, to, unit, buckets, summaryAcc.toMetrics(stepMinutes), rows);
    }

    private HeatmapAccumulator[] newAccumulators(int size) {
        HeatmapAccumulator[] array = new HeatmapAccumulator[size];
        for (int i = 0; i < size; i++) {
            array[i] = new HeatmapAccumulator();
        }
        return array;
    }

    private PoolHeatmapResponse.Metrics emptyMetrics(int stepMinutes) {
        return new HeatmapAccumulator().toMetrics(stepMinutes);
    }

    private Set<String> normalizeModels(Collection<String> models) {
        Set<String> result = new LinkedHashSet<>();
        if (models != null) {
            for (String model : models) {
                if (model != null && !model.isBlank()) {
                    result.add(model.trim());
                }
            }
        }
        return result;
    }

    /** 时间桶列表：从窗口起点对齐到粒度边界，逐步推进到窗口终点。 */
    private List<OffsetDateTime> buildBuckets(OffsetDateTime from, OffsetDateTime to, String unit) {
        List<OffsetDateTime> buckets = new ArrayList<>();
        OffsetDateTime cursor = truncate(from, unit);
        while (cursor.isBefore(to)) {
            buckets.add(cursor);
            cursor = switch (unit) {
                case "minute" -> cursor.plusMinutes(1);
                case "day" -> cursor.plusDays(1);
                default -> cursor.plusHours(1);
            };
        }
        return buckets;
    }

    private OffsetDateTime truncate(OffsetDateTime value, String unit) {
        return switch (unit) {
            case "minute" -> value.truncatedTo(ChronoUnit.MINUTES);
            case "day" -> value.toLocalDate().atStartOfDay(ZONE).toOffsetDateTime();
            default -> value.truncatedTo(ChronoUnit.HOURS);
        };
    }

    private int stepMinutes(String unit) {
        return switch (unit) {
            case "minute" -> 1;
            case "day" -> 24 * 60;
            default -> 60;
        };
    }

    /** 累加器：只存求和量，比率统一在最后一步算，保证「整行汇总」与「单桶」口径一致。 */
    private static final class HeatmapAccumulator {

        private long requests;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheCreationTokens;
        private long firstTokenSamples;
        private double firstTokenWeightedMs;
        private double durationSumMs;
        private BigDecimal actualCost = BigDecimal.ZERO;

        void add(PoolHeatmapBucket row) {
            long rowRequests = value(row.getRequests());
            requests += rowRequests;
            inputTokens += value(row.getInputTokens());
            outputTokens += value(row.getOutputTokens());
            cacheReadTokens += value(row.getCacheReadTokens());
            cacheCreationTokens += value(row.getCacheCreationTokens());
            long samples = value(row.getFirstTokenSamples());
            firstTokenSamples += samples;
            if (row.getAvgFirstTokenMs() != null) {
                firstTokenWeightedMs += row.getAvgFirstTokenMs() * samples;
            }
            if (row.getAvgDurationMs() != null) {
                durationSumMs += row.getAvgDurationMs() * rowRequests;
            }
            if (row.getTotalActualCost() != null) {
                actualCost = actualCost.add(row.getTotalActualCost());
            }
        }

        /** 合并另一个累加器：用于把「通过筛选的账号」重新汇总成顶部卡片。 */
        void merge(HeatmapAccumulator other) {
            requests += other.requests;
            inputTokens += other.inputTokens;
            outputTokens += other.outputTokens;
            cacheReadTokens += other.cacheReadTokens;
            cacheCreationTokens += other.cacheCreationTokens;
            firstTokenSamples += other.firstTokenSamples;
            firstTokenWeightedMs += other.firstTokenWeightedMs;
            durationSumMs += other.durationSumMs;
            actualCost = actualCost.add(other.actualCost);
        }

        PoolHeatmapResponse.Metrics toMetrics(int stepMinutes) {
            Double hitRate = cacheRate();
            Double avgFirstToken = firstTokenSamples > 0 ? firstTokenWeightedMs / firstTokenSamples : null;
            Double tokensPerSecond = durationSumMs > 0 ? outputTokens / (durationSumMs / 1000d) : null;
            Double rpm = stepMinutes > 0 ? requests / (double) stepMinutes : null;
            return new PoolHeatmapResponse.Metrics(
                    requests, hitRate, avgFirstToken, tokensPerSecond, rpm, actualCost);
        }

        private Double cacheRate() {
            long denominator = inputTokens + cacheReadTokens + cacheCreationTokens;
            if (denominator <= 0L) {
                return null;
            }
            return BigDecimal.valueOf(cacheReadTokens)
                    .divide(BigDecimal.valueOf(denominator), CACHE_RATE_SCALE, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        private static long value(Long value) {
            return value == null ? 0L : value;
        }
    }

    /** 查询号池账号绑定的本地密钥（可能为空）。 */
    private AccountApiKeyEntity findBoundKey(Long boundKeyId) {
        return boundKeyId == null ? null : apiKeyRepository.findById(boundKeyId).orElse(null);
    }

    /**
     * 查询单个号池账号的时序指标。
     *
     * @param range       时间维度：1d / 7d / 30d / 90d
     * @param granularity 聚合粒度：minute / hour / day，缺省时按 range 推断
     */
    public List<PoolSeriesPointResponse> series(Integer platformId, Long externalAccountId,
                                                String range, String granularity) {
        findAccount(platformId, externalAccountId);
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(resolveRange(range));
        String unit = resolveGranularity(granularity, range);

        List<PoolSeriesPointResponse> result = new ArrayList<>();
        for (PoolSeriesPoint point : poolSampleRepository.series(platformId, externalAccountId, from, to, unit)) {
            result.add(new PoolSeriesPointResponse(
                    point.getBucket(),
                    point.getRequests(),
                    point.getInputTokens(),
                    point.getOutputTokens(),
                    point.getCacheReadTokens(),
                    point.getCacheCreationTokens(),
                    point.getFirstTokenSamples(),
                    point.getAvgFirstTokenMs(),
                    point.getP95FirstTokenMs(),
                    point.getAvgDurationMs(),
                    point.getTotalActualCost(),
                    cacheHitRate(point.getInputTokens(), point.getCacheReadTokens(), point.getCacheCreationTokens())
            ));
        }
        return result;
    }

    /**
     * 查询单个号池账号按模型聚合的指标。
     */
    public List<PoolModelMetricsResponse> models(Integer platformId, Long externalAccountId, String range) {
        findAccount(platformId, externalAccountId);
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(resolveRange(range));

        List<PoolModelMetricsResponse> result = new ArrayList<>();
        for (PoolModelMetrics metrics : poolSampleRepository.byModel(platformId, externalAccountId, from, to)) {
            result.add(new PoolModelMetricsResponse(
                    metrics.getModel(),
                    metrics.getRequests(),
                    metrics.getInputTokens(),
                    metrics.getOutputTokens(),
                    metrics.getCacheReadTokens(),
                    metrics.getCacheCreationTokens(),
                    metrics.getFirstTokenSamples(),
                    metrics.getAvgFirstTokenMs(),
                    metrics.getP95FirstTokenMs(),
                    metrics.getAvgDurationMs(),
                    metrics.getTotalActualCost(),
                    cacheHitRate(metrics.getInputTokens(), metrics.getCacheReadTokens(), metrics.getCacheCreationTokens())
            ));
        }
        return result;
    }

    private PoolAccountEntity findAccount(Integer platformId, Long externalAccountId) {
        return poolAccountRepository.findByPlatformAndExternalId(platformId, externalAccountId)
                .orElseThrow(() -> BusinessException.of("号池账号不存在: " + externalAccountId));
    }

    private PoolAccountResponse toResponse(PoolAccountEntity account, PoolAccountMetrics metrics) {
        AccountApiKeyEntity key = findBoundKey(account.getBoundKeyId());
        String boundKeyName = key == null ? null : key.getKeyName();
        String boundKeyMasked = key == null ? null : key.getKeyMasked();
        return new PoolAccountResponse(
                account.getExternalAccountId(),
                account.getName(),
                account.getPlatform(),
                account.getAccountType(),
                account.getStatus(),
                account.getSchedulable(),
                account.getErrorMessage(),
                account.getRateLimitedAt(),
                account.getRateLimitResetAt(),
                account.getTempUnschedulableUntil(),
                account.getTempUnschedulableReason(),
                account.getConcurrency(),
                account.getPriority(),
                account.getRateMultiplier(),
                account.getLastUsedAt(),
                account.getBoundKeyId(),
                boundKeyName,
                boundKeyMasked,
                account.getLastSampleAt(),
                account.getLastSyncError(),
                metrics == null ? 0L : metrics.getRequests(),
                metrics == null ? 0L : metrics.getInputTokens(),
                metrics == null ? 0L : metrics.getOutputTokens(),
                metrics == null ? 0L : metrics.getCacheReadTokens(),
                metrics == null ? 0L : metrics.getCacheCreationTokens(),
                metrics == null ? 0L : metrics.getFirstTokenSamples(),
                metrics == null ? null : metrics.getAvgFirstTokenMs(),
                metrics == null ? null : metrics.getP95FirstTokenMs(),
                metrics == null ? null : metrics.getAvgDurationMs(),
                metrics == null ? BigDecimal.ZERO : metrics.getTotalCost(),
                metrics == null ? BigDecimal.ZERO : metrics.getTotalActualCost(),
                metrics == null ? null
                        : cacheHitRate(metrics.getInputTokens(), metrics.getCacheReadTokens(),
                        metrics.getCacheCreationTokens())
        );
    }

    /**
     * 缓存命中率 = 缓存读取 / (输入 + 缓存读取 + 缓存写入)。
     */
    private Double cacheHitRate(Long inputTokens, Long cacheReadTokens, Long cacheCreationTokens) {
        long input = inputTokens == null ? 0L : inputTokens;
        long read = cacheReadTokens == null ? 0L : cacheReadTokens;
        long creation = cacheCreationTokens == null ? 0L : cacheCreationTokens;
        long denominator = input + read + creation;
        if (denominator <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(read)
                .divide(BigDecimal.valueOf(denominator), CACHE_RATE_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 支持的时间维度：分钟级（1h / 6h / 12h）到天级（1d / 7d / 30d / 90d），未知值回退 7 天。 */
    private Duration resolveRange(String range) {
        if (range == null) {
            return Duration.ofDays(7);
        }
        return switch (range.trim().toLowerCase()) {
            case "90m" -> Duration.ofMinutes(90);
            case "1h" -> Duration.ofHours(1);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d", "24h" -> Duration.ofDays(1);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            default -> Duration.ofDays(7);
        };
    }

    /**
     * 解析时序聚合粒度：minute / hour / day。
     *
     * <p>直连库是秒级增量，短窗口（≤12 小时）默认按分钟出点，才能看出缓存率的实时波动；
     * 长窗口（≥30 天）默认按天，避免一次返回上万个桶。</p>
     *
     * <p>分钟粒度只对 1 天以内的窗口开放：90 天 × 每分钟 = 12.9 万个桶，既慢又没有意义，
     * 因此超出时自动降级为小时粒度。</p>
     */
    private String resolveGranularity(String granularity, String range) {
        Duration window = resolveRange(range);
        if (granularity != null) {
            String normalized = granularity.trim().toLowerCase();
            if ("minute".equals(normalized)) {
                return window.compareTo(Duration.ofDays(1)) <= 0 ? "minute" : "hour";
            }
            if ("hour".equals(normalized) || "day".equals(normalized)) {
                return normalized;
            }
        }
        if (window.compareTo(Duration.ofHours(12)) <= 0) {
            return "minute";
        }
        if (window.compareTo(Duration.ofDays(30)) >= 0) {
            return "day";
        }
        return "hour";
    }
}