package com.monitor.platform.api.controller;

import com.monitor.platform.api.dto.BindPoolAccountRequest;
import com.monitor.platform.api.dto.PoolAccountResponse;
import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.api.dto.PoolModelMetricsResponse;
import com.monitor.platform.api.dto.PoolSeriesPointResponse;
import com.monitor.platform.common.response.ApiResponse;
import com.monitor.platform.pool.PoolAccountBindService;
import com.monitor.platform.pool.PoolQueryService;
import com.monitor.platform.pool.PoolSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 号池监控接口。
 *
 * <p>号池指的是用户自建 Sub2API 平台上的账号（每个账号对应一个上游 Key）。
 * 这里只做读取与手动触发采集，所有写操作仍由平台页面完成。</p>
 */
@RestController
@RequestMapping("/api/v1/upstream/instances/{instanceId}/pool-accounts")
public class PoolMonitorController {

    private final PoolQueryService poolQueryService;
    private final PoolSyncService poolSyncService;
    private final PoolAccountBindService poolAccountBindService;

    public PoolMonitorController(PoolQueryService poolQueryService,
                                 PoolSyncService poolSyncService,
                                 PoolAccountBindService poolAccountBindService) {
        this.poolQueryService = poolQueryService;
        this.poolSyncService = poolSyncService;
        this.poolAccountBindService = poolAccountBindService;
    }

    /**
     * 查询平台下号池账号列表与聚合指标。
     *
     * @param instanceId 平台 ID
     * @param range      时间维度：1h / 6h / 12h / 1d（24h）/ 7d / 30d / 90d
     */
    @GetMapping
    public ApiResponse<List<PoolAccountResponse>> listPoolAccounts(
            @PathVariable Integer instanceId,
            @RequestParam(defaultValue = "7d") String range) {
        return ApiResponse.of(poolQueryService.listAccounts(instanceId, range), null);
    }

    /**
     * 直连库增量采集的运行状态：是否配置生效、游标位置、最新明细的滞后。
     *
     * <p>用来排查「分钟级缓存率为空」：未配置 / 未初始化 / 滞后过大都会在这里体现。</p>
     */
    @GetMapping("/ingest-status")
    public ApiResponse<PoolIngestStatusResponse> ingestStatus(@PathVariable Integer instanceId) {
        return ApiResponse.of(poolQueryService.ingestStatus(instanceId), null);
    }

    /**
     * 号池色块矩阵（热力图）趋势：行 = 平台 / 账号，列 = 等宽时间桶。
     *
     * @param instanceId  平台 ID
     * @param range       时间维度：90m / 6h / 12h / 1d（24h）/ 7d / 30d
     * @param granularity 聚合粒度：minute / hour / day，缺省按 range 推断
     * @param models      逗号分隔的模型过滤，缺省表示全部
     * @param accounts    逗号分隔的号池账号 ID 过滤，缺省表示全部
     * @param platforms   逗号分隔的账号上游平台过滤（openai / anthropic ...），缺省表示全部
     */
    @GetMapping("/heatmap")
    public ApiResponse<PoolHeatmapResponse> heatmap(
            @PathVariable Integer instanceId,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String models,
            @RequestParam(required = false) String accounts,
            @RequestParam(required = false) String platforms) {
        return ApiResponse.of(poolQueryService.heatmap(instanceId, range, granularity,
                splitCsv(models), splitCsvLong(accounts), splitCsv(platforms)), null);
    }

    /** 逗号分隔参数解析：空值返回空列表，非法项直接忽略，避免一个坏参数让整页 400。 */
    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static List<Long> splitCsvLong(String value) {
        List<Long> result = new ArrayList<>();
        for (String part : splitCsv(value)) {
            try {
                result.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
                // 非数字账号 ID 直接忽略
            }
        }
        return result;
    }

    /**
     * 平台下出现过的模型名，供色块矩阵的「模型」筛选使用。
     *
     * @param instanceId 平台 ID
     * @param range      时间维度，仅用于决定扫描窗口（最少 30 天）
     */
    @GetMapping("/model-options")
    public ApiResponse<List<String>> modelOptions(
            @PathVariable Integer instanceId,
            @RequestParam(defaultValue = "30d") String range) {
        return ApiResponse.of(poolQueryService.modelOptions(instanceId, range), null);
    }

    /**
     * 查询单个号池账号的时序指标。
     *
     * @param instanceId        平台 ID
     * @param externalAccountId 号池账号 ID
     * @param range             时间维度
     * @param granularity       聚合粒度：minute / hour / day，缺省按 range 推断
     */
    @GetMapping("/{externalAccountId}/series")
    public ApiResponse<List<PoolSeriesPointResponse>> listSeries(
            @PathVariable Integer instanceId,
            @PathVariable Long externalAccountId,
            @RequestParam(defaultValue = "7d") String range,
            @RequestParam(required = false) String granularity) {
        return ApiResponse.of(poolQueryService.series(instanceId, externalAccountId, range, granularity), null);
    }

    /**
     * 查询单个号池账号按模型聚合的指标。
     */
    @GetMapping("/{externalAccountId}/models")
    public ApiResponse<List<PoolModelMetricsResponse>> listModels(
            @PathVariable Integer instanceId,
            @PathVariable Long externalAccountId,
            @RequestParam(defaultValue = "7d") String range) {
        return ApiResponse.of(poolQueryService.models(instanceId, externalAccountId, range), null);
    }

    /**
     * 手动绑定号池账号与本地密钥；{@code keyId} 为空表示解除绑定。
     */
    @PostMapping("/{externalAccountId}/bind")
    public ApiResponse<Void> bind(
            @PathVariable Integer instanceId,
            @PathVariable Long externalAccountId,
            @RequestBody BindPoolAccountRequest request) {
        poolAccountBindService.bind(instanceId, externalAccountId, request == null ? null : request.keyId());
        return ApiResponse.of(null, null);
    }

    /**
     * 手动触发一次号池健康度同步与明细采集。
     */
    @PostMapping("/sync")
    public ApiResponse<Void> sync(@PathVariable Integer instanceId) {
        poolSyncService.syncHealth(instanceId);
        poolSyncService.syncSamples(instanceId);
        return ApiResponse.of(null, null);
    }
}