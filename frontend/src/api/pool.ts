import { apiRequest } from './client'
import type { PoolAccount, PoolCacheRateMatrix, PoolGranularity, PoolIngestStatus, PoolModelMetrics, PoolRange, PoolSeriesPoint } from '../types'

interface PoolAccountDto {
  externalAccountId: number
  name?: string | null
  platform?: string | null
  accountType?: string | null
  status?: string | null
  schedulable?: boolean | null
  errorMessage?: string | null
  rateLimitedAt?: string | null
  rateLimitResetAt?: string | null
  tempUnschedulableUntil?: string | null
  tempUnschedulableReason?: string | null
  concurrency?: number | null
  priority?: number | null
  rateMultiplier?: number | null
  lastUsedAt?: string | null
  boundKeyId?: number | null
  boundKeyName?: string | null
  boundKeyMasked?: string | null
  lastSampleAt?: string | null
  lastSyncError?: string | null
  requests?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheCreationTokens?: number | null
  firstTokenSamples?: number | null
  avgFirstTokenMs?: number | null
  p95FirstTokenMs?: number | null
  avgDurationMs?: number | null
  totalCost?: number | null
  totalActualCost?: number | null
  cacheHitRate?: number | null
}

interface PoolSeriesPointDto {
  bucket?: string
  requests?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheCreationTokens?: number | null
  firstTokenSamples?: number | null
  avgFirstTokenMs?: number | null
  p95FirstTokenMs?: number | null
  avgDurationMs?: number | null
  totalActualCost?: number | null
  cacheHitRate?: number | null
}

interface PoolModelMetricsDto {
  model?: string
  requests?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheCreationTokens?: number | null
  firstTokenSamples?: number | null
  avgFirstTokenMs?: number | null
  p95FirstTokenMs?: number | null
  avgDurationMs?: number | null
  totalActualCost?: number | null
  cacheHitRate?: number | null
}

function num(value: number | null | undefined): number {
  return value == null ? 0 : Number(value)
}

function nullableNum(value: number | null | undefined): number | null {
  return value == null ? null : Number(value)
}

/**
 * 查询平台下号池账号列表与聚合指标。
 */
export async function listPoolAccounts(platformId: number, range: PoolRange): Promise<PoolAccount[]> {
  const rows = await apiRequest<PoolAccountDto[]>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts?range=${range}`,
  )
  return rows.map(row => ({
    externalAccountId: Number(row.externalAccountId),
    name: row.name ?? null,
    platform: row.platform ?? null,
    accountType: row.accountType ?? null,
    status: row.status ?? null,
    schedulable: row.schedulable ?? null,
    errorMessage: row.errorMessage ?? null,
    rateLimitedAt: row.rateLimitedAt ?? null,
    rateLimitResetAt: row.rateLimitResetAt ?? null,
    tempUnschedulableUntil: row.tempUnschedulableUntil ?? null,
    tempUnschedulableReason: row.tempUnschedulableReason ?? null,
    concurrency: row.concurrency ?? null,
    priority: row.priority ?? null,
    rateMultiplier: nullableNum(row.rateMultiplier),
    lastUsedAt: row.lastUsedAt ?? null,
    boundKeyId: row.boundKeyId ?? null,
    boundKeyName: row.boundKeyName ?? null,
    boundKeyMasked: row.boundKeyMasked ?? null,
    lastSampleAt: row.lastSampleAt ?? null,
    lastSyncError: row.lastSyncError ?? null,
    requests: num(row.requests),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    cacheReadTokens: num(row.cacheReadTokens),
    cacheCreationTokens: num(row.cacheCreationTokens),
    firstTokenSamples: num(row.firstTokenSamples),
    avgFirstTokenMs: nullableNum(row.avgFirstTokenMs),
    p95FirstTokenMs: nullableNum(row.p95FirstTokenMs),
    avgDurationMs: nullableNum(row.avgDurationMs),
    totalCost: num(row.totalCost),
    totalActualCost: num(row.totalActualCost),
    cacheHitRate: nullableNum(row.cacheHitRate),
  }))
}

/**
 * 查询单个号池账号的时序指标。
 */
export async function listPoolSeries(
  platformId: number,
  externalAccountId: number,
  range: PoolRange,
  granularity?: PoolGranularity,
): Promise<PoolSeriesPoint[]> {
  const query = granularity ? `?range=${range}&granularity=${granularity}` : `?range=${range}`
  const rows = await apiRequest<PoolSeriesPointDto[]>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/${externalAccountId}/series${query}`,
  )
  return rows.map(row => ({
    bucket: row.bucket || new Date().toISOString(),
    requests: num(row.requests),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    cacheReadTokens: num(row.cacheReadTokens),
    cacheCreationTokens: num(row.cacheCreationTokens),
    firstTokenSamples: num(row.firstTokenSamples),
    avgFirstTokenMs: nullableNum(row.avgFirstTokenMs),
    p95FirstTokenMs: nullableNum(row.p95FirstTokenMs),
    avgDurationMs: nullableNum(row.avgDurationMs),
    totalActualCost: num(row.totalActualCost),
    cacheHitRate: nullableNum(row.cacheHitRate),
  }))
}

/**
 * 查询单个号池账号按模型聚合的指标。
 */
export async function listPoolModels(
  platformId: number,
  externalAccountId: number,
  range: PoolRange,
): Promise<PoolModelMetrics[]> {
  const rows = await apiRequest<PoolModelMetricsDto[]>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/${externalAccountId}/models?range=${range}`,
  )
  return rows.map(row => ({
    model: row.model || 'unknown',
    requests: num(row.requests),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    cacheReadTokens: num(row.cacheReadTokens),
    cacheCreationTokens: num(row.cacheCreationTokens),
    firstTokenSamples: num(row.firstTokenSamples),
    avgFirstTokenMs: nullableNum(row.avgFirstTokenMs),
    p95FirstTokenMs: nullableNum(row.p95FirstTokenMs),
    avgDurationMs: nullableNum(row.avgDurationMs),
    totalActualCost: num(row.totalActualCost),
    cacheHitRate: nullableNum(row.cacheHitRate),
  }))
}

interface PoolIngestStatusDto {
  enabled?: boolean
  configured?: boolean
  passwordConfigured?: boolean
  lastUsageLogId?: number | null
  lastRunAt?: string | null
  latestSampleAt?: string | null
  lagSeconds?: number | null
}

/**
 * 查询号池直连库增量采集状态，用于判断分钟级缓存率是否真的有数据。
 */
export async function getPoolIngestStatus(platformId: number): Promise<PoolIngestStatus> {
  const dto = await apiRequest<PoolIngestStatusDto>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/ingest-status`,
  )
  return {
    enabled: dto?.enabled ?? false,
    configured: dto?.configured ?? false,
    passwordConfigured: dto?.passwordConfigured ?? false,
    lastUsageLogId: num(dto?.lastUsageLogId),
    lastRunAt: dto?.lastRunAt ?? null,
    latestSampleAt: dto?.latestSampleAt ?? null,
    lagSeconds: dto?.lagSeconds == null ? null : Number(dto.lagSeconds),
  }
}

/**
 * 手动绑定号池账号与本地密钥；keyId 为空表示解除绑定。
 */
export async function bindPoolAccount(platformId: number, externalAccountId: number, keyId: number | null): Promise<void> {
  await apiRequest<void>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/${externalAccountId}/bind`,
    { method: 'POST', body: JSON.stringify({ keyId }) },
  )
}

/**
 * 手动触发一次号池健康度同步与明细采集。
 */
export async function syncPoolPlatform(platformId: number): Promise<void> {
  await apiRequest<void>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/sync`,
    { method: 'POST' },
  )
}
interface PoolCacheRateMatrixDto {
  windows?: Array<{ key?: string; label?: string; from?: string }>
  minimumSample?: number | null
  accounts?: Array<{
    externalAccountId: number
    name?: string | null
    platform?: string | null
    boundKeyName?: string | null
    boundKeyMasked?: string | null
    cells?: Array<{
      key?: string
      requests?: number | null
      cacheHitRate?: number | null
      cacheRateNumerator?: number | null
      cacheRateDenominator?: number | null
      inputTokens?: number | null
      cacheReadTokens?: number | null
      cacheCreationTokens?: number | null
      firstTokenSamples?: number | null
      avgFirstTokenMs?: number | null
      avgDurationMs?: number | null
    }>
  }>
}

/**
 * 多时间窗缓存率对比矩阵：行=号池账号，列=时间窗。
 *
 * @param windows 时间窗 key 列表，如 ['1h','24h','7d']，最多 6 个
 */
export async function listPoolCacheRates(platformId: number, windows: string[]): Promise<PoolCacheRateMatrix> {
  const query = windows.length ? `?windows=${encodeURIComponent(windows.join(','))}` : ''
  const dto = await apiRequest<PoolCacheRateMatrixDto>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/cache-rates${query}`,
  )
  return {
    windows: (dto?.windows ?? []).map(item => ({
      key: item.key || '',
      label: item.label || item.key || '',
      from: item.from || '',
    })),
    minimumSample: num(dto?.minimumSample),
    accounts: (dto?.accounts ?? []).map(row => ({
      externalAccountId: Number(row.externalAccountId),
      name: row.name ?? null,
      platform: row.platform ?? null,
      boundKeyName: row.boundKeyName ?? null,
      boundKeyMasked: row.boundKeyMasked ?? null,
      cells: (row.cells ?? []).map(cell => ({
        key: cell.key || '',
        requests: num(cell.requests),
        cacheHitRate: nullableNum(cell.cacheHitRate),
        cacheRateNumerator: num(cell.cacheRateNumerator),
        cacheRateDenominator: num(cell.cacheRateDenominator),
        inputTokens: cell.inputTokens == null ? null : num(cell.inputTokens),
        cacheReadTokens: cell.cacheReadTokens == null ? null : num(cell.cacheReadTokens),
        cacheCreationTokens: cell.cacheCreationTokens == null ? null : num(cell.cacheCreationTokens),
        firstTokenSamples: cell.firstTokenSamples == null ? null : num(cell.firstTokenSamples),
        avgFirstTokenMs: nullableNum(cell.avgFirstTokenMs),
        avgDurationMs: nullableNum(cell.avgDurationMs),
      })),
    })),
  }
}