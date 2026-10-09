import { apiRequest } from './client'
import type { PoolAccount, PoolGranularity, PoolHeatmap, PoolHeatmapMetrics, PoolIngestStatus, PoolModelMetrics, PoolRange, PoolSeriesPoint } from '../types'

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

interface PoolHeatmapMetricsDto {
  requests?: number | null
  cacheHitRate?: number | null
  avgFirstTokenMs?: number | null
  tokensPerSecond?: number | null
  rpm?: number | null
  actualCost?: number | null
}

interface PoolHeatmapDto {
  from?: string
  to?: string
  granularity?: string
  buckets?: string[]
  summary?: PoolHeatmapMetricsDto
  rows?: Array<{
    externalAccountId: number
    name?: string | null
    platform?: string | null
    boundKeyName?: string | null
    boundKeyMasked?: string | null
    total?: PoolHeatmapMetricsDto
    cells?: PoolHeatmapMetricsDto[]
  }>
}

function mapHeatmapMetrics(dto: PoolHeatmapMetricsDto | undefined | null): PoolHeatmapMetrics {
  return {
    requests: num(dto?.requests),
    cacheHitRate: nullableNum(dto?.cacheHitRate),
    avgFirstTokenMs: nullableNum(dto?.avgFirstTokenMs),
    tokensPerSecond: nullableNum(dto?.tokensPerSecond),
    rpm: nullableNum(dto?.rpm),
    actualCost: num(dto?.actualCost),
  }
}

/**
 * 号池色块矩阵趋势：行 = 平台 / 账号，列 = 等宽时间桶。
 *
 * @param range       90m / 6h / 12h / 1d / 7d / 30d
 * @param granularity minute / hour / day，缺省由后端按 range 推断
 * @param models      模型过滤
 * @param accounts    号池账号 ID 过滤
 */
export async function getPoolHeatmap(
  platformId: number,
  params: { range: PoolRange; granularity?: PoolGranularity; models?: string[]; accounts?: number[]; platforms?: string[] },
): Promise<PoolHeatmap> {
  const query = new URLSearchParams({ range: params.range })
  if (params.granularity) query.set('granularity', params.granularity)
  if (params.models?.length) query.set('models', params.models.join(','))
  if (params.accounts?.length) query.set('accounts', params.accounts.join(','))
  if (params.platforms?.length) query.set('platforms', params.platforms.join(','))
  const dto = await apiRequest<PoolHeatmapDto>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/heatmap?${query.toString()}`,
  )
  return {
    from: dto?.from || new Date().toISOString(),
    to: dto?.to || new Date().toISOString(),
    granularity: (dto?.granularity as PoolGranularity) || 'hour',
    buckets: dto?.buckets ?? [],
    summary: mapHeatmapMetrics(dto?.summary),
    rows: (dto?.rows ?? []).map(row => ({
      externalAccountId: Number(row.externalAccountId),
      name: row.name ?? null,
      platform: row.platform ?? null,
      boundKeyName: row.boundKeyName ?? null,
      boundKeyMasked: row.boundKeyMasked ?? null,
      total: mapHeatmapMetrics(row.total),
      cells: (row.cells ?? []).map(mapHeatmapMetrics),
    })),
  }
}

/**
 * 平台下出现过的模型名（用于色块矩阵的「模型」筛选）。
 */
export async function listPoolModelOptions(platformId: number, range: PoolRange = '30d'): Promise<string[]> {
  const rows = await apiRequest<string[]>(
    `/api/v1/upstream/instances/${platformId}/pool-accounts/model-options?range=${range}`,
  )
  return rows ?? []
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
