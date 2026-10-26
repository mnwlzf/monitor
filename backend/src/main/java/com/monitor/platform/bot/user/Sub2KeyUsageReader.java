package com.monitor.platform.bot.user;

import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Sub2API 只读库读取指定 API Key 的用量明细。
 *
 * <p>一次最多查询 3 个 Key；总计按 api_key_id 聚合，模型明细按
 * api_key_id + model 聚合，均使用 api_key_id + created_at 索引。</p>
 */
@Component
public class Sub2KeyUsageReader {

    private static final String KEY_TOTALS = """
            SELECT api_key_id,
                   COUNT(*) AS request_count,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(cache_creation_tokens), 0) AS cache_creation_tokens,
                   COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
                   COALESCE(SUM(total_cost), 0) AS standard_cost,
                   COALESCE(SUM(actual_cost), 0) AS actual_cost,
                   COALESCE(AVG(duration_ms), 0) AS average_duration_ms
            FROM usage_logs
            WHERE api_key_id IN (%s)
              AND created_at >= ?
              AND created_at < ?
            GROUP BY api_key_id
            """;

    private static final String MODEL_USAGE = """
            SELECT api_key_id,
                   model,
                   COUNT(*) AS request_count,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(cache_creation_tokens), 0) AS cache_creation_tokens,
                   COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
                   COALESCE(SUM(total_cost), 0) AS standard_cost,
                   COALESCE(SUM(actual_cost), 0) AS actual_cost,
                   COALESCE(AVG(duration_ms), 0) AS average_duration_ms
            FROM usage_logs
            WHERE api_key_id IN (%s)
              AND created_at >= ?
              AND created_at < ?
            GROUP BY api_key_id, model
            ORDER BY api_key_id, actual_cost DESC, model
            """;

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2KeyUsageReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 每个 Key 的总用量；没有记录的 Key 返回 0 值。 */
    public Map<Long, KeyUsage> byKeyIds(List<Long> keyIds, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, KeyUsage> result = new HashMap<>();
        if (keyIds == null || keyIds.isEmpty()) {
            return result;
        }
        for (Long id : keyIds) {
            if (id != null) {
                result.put(id, KeyUsage.empty(id));
            }
        }
        List<KeyUsage> rows = jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(sql(KEY_TOTALS, keyIds.size()));
            bind(statement, keyIds, from, to);
            return statement;
        }, (rs, rowNum) -> new KeyUsage(
                rs.getLong("api_key_id"),
                rs.getLong("request_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getBigDecimal("standard_cost"),
                rs.getBigDecimal("actual_cost"),
                rs.getDouble("average_duration_ms")
        ));
        for (KeyUsage row : rows) {
            result.put(row.apiKeyId(), row);
        }
        return result;
    }

    /** 每个 Key 按模型拆分的用量。 */
    public List<ModelUsage> byKeyAndModel(List<Long> keyIds, OffsetDateTime from, OffsetDateTime to) {
        if (keyIds == null || keyIds.isEmpty()) {
            return List.of();
        }
        return jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(sql(MODEL_USAGE, keyIds.size()));
            bind(statement, keyIds, from, to);
            return statement;
        }, (rs, rowNum) -> new ModelUsage(
                rs.getLong("api_key_id"),
                rs.getString("model"),
                rs.getLong("request_count"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getBigDecimal("standard_cost"),
                rs.getBigDecimal("actual_cost"),
                rs.getDouble("average_duration_ms")
        ));
    }

    private static String sql(String template, int count) {
        return template.formatted(String.join(",", Collections.nCopies(count, "?")));
    }

    private static void bind(java.sql.PreparedStatement statement, List<Long> ids,
                             OffsetDateTime from, OffsetDateTime to) throws java.sql.SQLException {
        int index = 1;
        for (Long id : ids) {
            statement.setLong(index++, id);
        }
        statement.setObject(index++, from);
        statement.setObject(index, to);
    }

    public record KeyUsage(
            long apiKeyId,
            long requestCount,
            long inputTokens,
            long outputTokens,
            long cacheCreationTokens,
            long cacheReadTokens,
            BigDecimal standardCost,
            BigDecimal actualCost,
            double averageDurationMs
    ) {
        public static KeyUsage empty(long apiKeyId) {
            return new KeyUsage(apiKeyId, 0, 0, 0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        public long totalTokens() {
            return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
        }
    }

    public record ModelUsage(
            long apiKeyId,
            String model,
            long requestCount,
            long inputTokens,
            long outputTokens,
            long cacheCreationTokens,
            long cacheReadTokens,
            BigDecimal standardCost,
            BigDecimal actualCost,
            double averageDurationMs
    ) {
        public long totalTokens() {
            return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
        }
    }
}
