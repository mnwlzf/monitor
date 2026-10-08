export type PlatformType = 'newapi' | 'sub2api'

export interface Platform {
  id: number
  name: string
  type: PlatformType
  url: string
  status: boolean
  accountCount: number
  lastCollectedAt: string | null
  /** 是否已配置 Sub2API 管理员密钥（用于号池监控）。 */
  adminKeyConfigured?: boolean
}

export interface Account {
  id: number
  platformId: number
  platformName: string
  platformType: PlatformType
  displayName: string
  loginName: string
  authType: 'PASSWORD' | 'TOKEN'
  status: boolean
  balance: number
  frozenBalance: number
  quota: number
  usedQuota: number
  quotaUnit: string
  requestCount: number
  credentialStatus: 'VALID' | 'INVALID' | 'UNKNOWN'
  lastCollectStatus: 'SUCCESS' | 'FAILED' | 'RUNNING' | 'PARTIAL' | 'UNKNOWN'
  lastCollectedAt: string | null
  nextCollectAt: string | null
}

export interface MetricPoint {
  time: string
  balance: number
  usedQuota: number
}

/** 余额详情页可选时间维度。 */
export type MetricRange = '1d' | '7d' | '30d' | '90d'

/**
 * 账号指标时序点（余额详情折线图）。
 *
 * balance 统一为 USD；quota / usedQuota 为上游原始额度单位，部分平台为 null。
 */
export interface AccountMetricPoint {
  collectedAt: string
  balance: number | null
  frozenBalance: number | null
  quota: number | null
  usedQuota: number | null
  requestCount: number | null
  quotaUnit: string | null
}

export interface Channel {
  id: string
  name: string
  platformId: number
  platformName: string
  platformType: PlatformType
  platform: string
  ratio: number
  baseRatio: number | null
  status: string
  accountName: string
}

export interface ChangeEvent {
  id: string
  type: string
  platformId: number
  platformName: string
  platformType: PlatformType
  entity: string
  field: string
  oldValue: string
  newValue: string
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  detectedAt: string
  message: string
  /** 是否为「正在使用（启用）」密钥发生的变更，仅 API_KEY 事件有意义。 */
  inUse: boolean
}

/**
 * 账号下的 API Key。
 *
 * 完整明文不随列表下发，仅返回脱敏值；平台特有明细放在 metrics 中。
 */
export interface ApiKey {
  id: number
  accountId: number
  platformId: number
  accountName: string
  platformType: PlatformType
  externalKeyId: string
  keyName: string | null
  keyMasked: string | null
  status: string
  upstreamStatus: string | null
  groupName: string | null
  groupPlatform: string | null
  unlimitedQuota: boolean | null
  remainQuota: number | null
  usedQuota: number | null
  quotaUnit: string | null
  modelLimitsEnabled: boolean | null
  modelLimits: string | null
  allowIps: string | null
  expiresAt: string | null
  upstreamCreatedAt: string | null
  lastUsedAt: string | null
  active: boolean
  firstSeenAt: string | null
  lastSeenAt: string | null
  lastChangedAt: string | null
  metrics: Record<string, unknown>
}
/**
 * 账号用量看板快照。
 *
 * 公共指标跨平台语义一致；平台特有明细放在 metrics 中，
 * 平台未提供的字段为 null，由界面降级展示。
 */
export interface UsageDashboard {
  id: number
  accountId: number
  platformId: number
  displayName: string
  platformType: PlatformType
  balance: number | null
  frozenBalance: number | null
  totalRequests: number | null
  totalTokens: number | null
  totalCost: number | null
  totalActualCost: number | null
  metrics: Record<string, unknown>
  platformStats: Array<Record<string, unknown>>
  collectedAt: string | null
}

/**
 * 页面可配置的定时任务。
 */
export interface ScheduledTask {
  id: number
  taskName: string
  taskCode: string
  handlerName: string
  cronExpression: string
  timezone: string
  enabled: boolean
  description: string | null
  lastRunAt: string | null
  lastRunStatus: 'RUNNING' | 'SUCCESS' | 'FAILED' | null
  lastRunMessage: string | null
}

/**
 * 定时任务处理器，即页面可选择的任务类型。
 */
export interface ScheduledTaskHandler {
  code: string
  name: string
  description: string
}

/**
 * 用户角色：ADMIN 可读写，VIEWER 只读。
 */
export type UserRole = 'ADMIN' | 'VIEWER'

/**
 * 当前登录用户。
 */
export interface CurrentUser {
  username: string
  roles: UserRole[]
  admin: boolean
}

/**
 * 页面可配置的 SMTP 邮件设置。
 *
 * 出于安全考虑后端不返回密码明文，仅通过 passwordConfigured 告知是否已配置。
 */
export interface MailSettings {
  enabled: boolean
  host: string | null
  port: number
  username: string | null
  passwordConfigured: boolean
  from: string | null
  fromName: string | null
  useTls: boolean
  updatedAt: string | null
}

/**
 * 邮件事件场景：不同事件使用各自的收件人。
 */
export type MailScene = 'BALANCE_ALERT' | 'DAILY_REPORT' | 'API_KEY_CHANGE'

/**
 * 邮件收件人（按事件场景区分）。
 */
export interface MailRecipient {
  id: number
  scene: MailScene
  email: string
  name: string | null
  createdAt: string | null
}

/**
 * 号池监控：账号健康状态与时间窗聚合指标。
 *
 * 号池指的是用户自建 Sub2API 平台上的账号，每个账号对应一个上游 Key。
 * requests / tokens / cost 等为空时表示该时间窗内没有明细样本。
 */
export interface PoolAccount {
  externalAccountId: number
  name: string | null
  platform: string | null
  accountType: string | null
  status: string | null
  schedulable: boolean | null
  errorMessage: string | null
  rateLimitedAt: string | null
  rateLimitResetAt: string | null
  tempUnschedulableUntil: string | null
  tempUnschedulableReason: string | null
  concurrency: number | null
  priority: number | null
  rateMultiplier: number | null
  lastUsedAt: string | null
  boundKeyId: number | null
  boundKeyName: string | null
  boundKeyMasked: string | null
  lastSampleAt: string | null
  lastSyncError: string | null
  requests: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
  firstTokenSamples: number
  avgFirstTokenMs: number | null
  p95FirstTokenMs: number | null
  avgDurationMs: number | null
  totalCost: number
  totalActualCost: number
  cacheHitRate: number | null
}

/** 号池监控可选时间维度。 */
export type PoolRange = '1d' | '7d' | '30d' | '90d'

/** 号池时序聚合粒度。 */
export type PoolGranularity = 'hour' | 'day'

/** 号池指标时序点。 */
export interface PoolSeriesPoint {
  bucket: string
  requests: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
  firstTokenSamples: number
  avgFirstTokenMs: number | null
  p95FirstTokenMs: number | null
  avgDurationMs: number | null
  totalActualCost: number
  cacheHitRate: number | null
}

/** 号池按模型聚合指标。 */
export interface PoolModelMetrics {
  model: string
  requests: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
  firstTokenSamples: number
  avgFirstTokenMs: number | null
  p95FirstTokenMs: number | null
  avgDurationMs: number | null
  totalActualCost: number
  cacheHitRate: number | null
}

/**
 * 邮件通知设置（余额提醒）。
 */
export interface NotificationSettings {
  balanceAlertEnabled: boolean
  balanceThreshold: number
  alertIntervalMinutes: number
  updatedAt: string | null
}