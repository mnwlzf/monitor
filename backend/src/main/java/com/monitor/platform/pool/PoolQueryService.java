package com.monitor.platform.pool;

import com.monitor.platform.api.dto.PoolAccountResponse;
import com.monitor.platform.api.dto.PoolCacheRateMatrixResponse;
import com.monitor.platform.api.dto.PoolModelMetricsResponse;
import com.monitor.platform.api.dto.PoolSeriesPointResponse;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 多时间窗对比默认列。 */
    private static final String DEFAULT_WINDOWS = "1h,6h,24h,7d,30d";
    /** 多时间窗对比最多允许的列数，避免前端表格过宽、后端聚合次数过多。 */
    private static final int MAX_WINDOWS = 6;
    /** 支持的时间窗预设：key -> [标签, 时长]。 */
    private static final Map<String, Object[]> WINDOW_PRESETS = windowPresets();

    private static Map<String, Object[]> windowPresets() {
        Map<String, Object[]> presets = new LinkedHashMap<>();
        presets.put("1h", new Object[]{"近 1 小时", Duration.ofHours(1)});
        presets.put("6h", new Object[]{"近 6 小时", Duration.ofHours(6)});
        presets.put("12h", new Object[]{"近 12 小时", Duration.ofHours(12)});
        presets.put("24h", new Object[]{"近 24 小时", Duration.ofHours(24)});
        presets.put("7d", new Object[]{"近 7 天", Duration.ofDays(7)});
        presets.put("30d", new Object[]{"近 30 天", Duration.ofDays(30)});
        presets.put("90d", new Object[]{"近 90 天", Duration.ofDays(90)});
        return presets;
    }

    private final PoolAccountRepository poolAccountRepository;
    private final PoolSampleRepository poolSampleRepository;
    private final AccountApiKeyRepository apiKeyRepository;

    public PoolQueryService(PoolAccountRepository poolAccountRepository,
                            PoolSampleRepository poolSampleRepository,
                            AccountApiKeyRepository apiKeyRepository) {
        this.poolAccountRepository = poolAccountRepository;
        this.poolSampleRepository = poolSampleRepository;
        this.apiKeyRepository = apiKeyRepository;
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
     * 多时间窗缓存率对比矩阵。
     *
     * <p>每个时间窗做一次平台级聚合（{@code pool_request_samples.created_at} 有索引，
     * 单次查询很轻），再按账号拼成「行=账号、列=时间窗」的表格。</p>
     *
     * @param platformId   平台 ID
     * @param windowsParam 逗号分隔的时间窗，如 {@code 1h,6h,24h,7d,30d}；非法或为空时用默认值
     */
    public PoolCacheRateMatrixResponse cacheRateMatrix(Integer platformId, String windowsParam) {
        List<String> keys = resolveWindows(windowsParam);
        OffsetDateTime to = OffsetDateTime.now();

        Map<String, Map<Long, PoolAccountMetrics>> metricsByWindow = new LinkedHashMap<>();
        for (String key : keys) {
            OffsetDateTime from = to.minus(windowDuration(key));
            Map<Long, PoolAccountMetrics> bucket = new HashMap<>();
            for (PoolAccountMetrics metrics : poolSampleRepository.aggregateByPlatform(platformId, from, to)) {
                if (metrics.getExternalAccountId() != null) {
                    bucket.put(metrics.getExternalAccountId(), metrics);
                }
            }
            metricsByWindow.put(key, bucket);
        }

        List<PoolCacheRateMatrixResponse.Window> windows = new ArrayList<>(keys.size());
        for (String key : keys) {
            windows.add(new PoolCacheRateMatrixResponse.Window(key, windowLabel(key), to.minus(windowDuration(key))));
        }

        List<PoolCacheRateMatrixResponse.AccountRow> rows = new ArrayList<>();
        for (PoolAccountEntity account : poolAccountRepository.findByPlatform(platformId)) {
            List<PoolCacheRateMatrixResponse.Cell> cells = new ArrayList<>(keys.size());
            long totalRequests = 0L;
            for (String key : keys) {
                PoolAccountMetrics metrics = metricsByWindow.get(key).get(account.getExternalAccountId());
                totalRequests += metrics == null || metrics.getRequests() == null ? 0L : metrics.getRequests();
                cells.add(toCell(key, metrics));
            }
            AccountApiKeyEntity key = findBoundKey(account.getBoundKeyId());
            rows.add(new PoolCacheRateMatrixResponse.AccountRow(
                    account.getExternalAccountId(),
                    account.getName(),
                    account.getPlatform(),
                    key == null ? null : key.getKeyName(),
                    key == null ? null : key.getKeyMasked(),
                    cells));
        }
        // 活跃账号排在前面，避免大量零请求账号把有数据的一屏挤下去
        rows.sort(Comparator.comparingLong(this::sumRequests).reversed()
                .thenComparing(row -> row.name() == null ? "" : row.name()));

        return new PoolCacheRateMatrixResponse(windows, rows);
    }

    private long sumRequests(PoolCacheRateMatrixResponse.AccountRow row) {
        long total = 0L;
        for (PoolCacheRateMatrixResponse.Cell cell : row.cells()) {
            total += cell.requests();
        }
        return total;
    }

    private PoolCacheRateMatrixResponse.Cell toCell(String key, PoolAccountMetrics metrics) {
        return new PoolCacheRateMatrixResponse.Cell(
                key,
                metrics == null || metrics.getRequests() == null ? 0L : metrics.getRequests(),
                metrics == null ? null
                        : cacheHitRate(metrics.getInputTokens(), metrics.getCacheReadTokens(),
                        metrics.getCacheCreationTokens()),
                metrics == null ? null : metrics.getInputTokens(),
                metrics == null ? null : metrics.getCacheReadTokens(),
                metrics == null ? null : metrics.getCacheCreationTokens(),
                metrics == null ? null : metrics.getFirstTokenSamples(),
                metrics == null ? null : metrics.getAvgFirstTokenMs(),
                metrics == null ? null : metrics.getAvgDurationMs()
        );
    }

    /** 解析时间窗参数，去重、限制列数，非法值忽略；全部非法时回退默认值。 */
    private List<String> resolveWindows(String windowsParam) {
        List<String> keys = new ArrayList<>();
        String source = windowsParam == null || windowsParam.isBlank() ? DEFAULT_WINDOWS : windowsParam;
        for (String raw : source.split(",")) {
            String key = raw.trim().toLowerCase();
            if (key.isEmpty() || !WINDOW_PRESETS.containsKey(key) || keys.contains(key)) {
                continue;
            }
            keys.add(key);
            if (keys.size() >= MAX_WINDOWS) {
                break;
            }
        }
        if (keys.isEmpty()) {
            for (String key : DEFAULT_WINDOWS.split(",")) {
                keys.add(key.trim());
            }
        }
        return keys;
    }

    private String windowLabel(String key) {
        Object[] preset = WINDOW_PRESETS.get(key);
        return preset == null ? key : (String) preset[0];
    }

    private Duration windowDuration(String key) {
        Object[] preset = WINDOW_PRESETS.get(key);
        return preset == null ? Duration.ofHours(1) : (Duration) preset[1];
    }

    /** 查询号池账号绑定的本地密钥（可能为空）。 */
    private AccountApiKeyEntity findBoundKey(Long boundKeyId) {
        return boundKeyId == null ? null : apiKeyRepository.findById(boundKeyId).orElse(null);
    }

    /**
     * 查询单个号池账号的时序指标。
     *
     * @param range       时间维度：1d / 7d / 30d / 90d
     * @param granularity 聚合粒度：hour / day，缺省时按 range 推断
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

    private Duration resolveRange(String range) {
        if (range == null) {
            return Duration.ofDays(7);
        }
        return switch (range.trim().toLowerCase()) {
            case "1d", "24h" -> Duration.ofDays(1);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            default -> Duration.ofDays(7);
        };
    }

    private String resolveGranularity(String granularity, String range) {
        if (granularity != null) {
            String normalized = granularity.trim().toLowerCase();
            if ("hour".equals(normalized) || "day".equals(normalized)) {
                return normalized;
            }
        }
        if (range != null) {
            String normalized = range.trim().toLowerCase();
            if ("30d".equals(normalized) || "90d".equals(normalized)) {
                return "day";
            }
        }
        return "hour";
    }
}