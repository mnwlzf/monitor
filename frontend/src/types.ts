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
  /**
   * 是否作为号池监控源。
   *
   * 只有「用户自己搭建的 Sub2API」才提供管理员只读接口，因此需要显式标记；
   * 其余 Sub2API / New API 只是它的上游，不参与号池监控。
   */
  poolMonitoringEnabled?: boolean
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
  /** 变更实体类型：GROUP（渠道）/ API_KEY（密钥）。 */
  entityType: string
  /** 上游实体 ID（渠道 ID / 密钥 ID），名字取不到时兜底展示。 */
  entity: string
  /** 变更对象的名字：渠道名 / 上游密钥名；取不到时与 entity 相同。 */
  entityName: string
  /** 变更所属账号 ID。 */
  accountId: number
  /** 变更所属账号名（上游账号）。 */
  accountName: string
  /** 上游密钥当前状态（仅密钥事件）：ACTIVE = 上游把 Key 标成「活跃」。 */
  keyStatus: string
  /** 受影响的「上游账号 Key」名字（多个用「、」连接）。 */
  upstreamKeyNames: string
  /** 变更渠道下的上游 Key 名称（渠道事件，严格按渠道名匹配；取不到为 null）。 */
  channelKeyNames: string | null
  /** 绑定了这些 Key 的本平台号池账号名（渠道事件；取不到为 null）。 */
  channelPoolAccounts: string | null
  /** 对应的「本平台 Key」名字（号池账号名，多个用「、」连接）。 */
  localKeyNames: string
  /** 所在分组的当前倍率（渠道事件）。 */
  groupRatio: number | null
  /** 渠道当前是否在售（上游启用，渠道事件）：true = 上游标为「活跃」。 */
  groupActive: boolean | null
  /** 渠道倍率变化对我平台的影响（一行 = 本平台账号 × 分组）；非倍率事件为空。 */
  impacts: ChangeImpact[]
  field: string
  oldValue: string
  newValue: string
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  detectedAt: string
  message: string
  /**
   * 变更是否涉及「我正在使用」的对象（密钥与渠道同口径）。
   *
   * 密钥 = 本平台号池账号绑定了这把上游 Key；渠道 = 这个渠道里有我绑定的 Key。
   * 上游自己把 Key / 渠道标成启用只是「活跃」（见 keyStatus / groupActive）。
   */
  inUse: boolean
}

/**
 * 账号下的 API Key。
 *
 * 完整明文不随列表下发，仅返回脱敏值；平台特有明细放在 metrics 中。
 */
/**
 * 渠道倍率变化对本平台的影响。
 *
 * `affected` = 上游新倍率已超过该分组倍率（倒挂，按这个分组卖会亏）。
 */
export interface ChangeImpact {
  /** 本平台号池账号名（= 我自己平台的 Key 名）。 */
  accountName: string
  externalAccountId: number | null
  /** 上游 Key 名（account_api_keys.key_name，绑定关系匹配到的那把）。 */
  upstreamKeyName: string
  /** 本平台账号所属分组名。 */
  groupName: string
  /** 本平台分组所属平台（openai / anthropic …）。 */
  groupPlatform: string
  groupRatio: number | null
  oldRatio: number | null
  newRatio: number | null
  increasePercent: number | null
  affected: boolean
  level: 'INFO' | 'WARNING' | 'CRITICAL'
}
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
export type MailScene = 'BALANCE_ALERT' | 'DAILY_REPORT' | 'API_KEY_CHANGE' | 'CHANNEL_ALERT' | 'CHANNEL_DIGEST' | 'ERROR_ALERT'

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

/** QQ 消息接收对象类型：群 / 私聊。 */
export type QqTargetType = 'GROUP' | 'PRIVATE'

/**
 * QQ 消息接收人（按事件场景区分）。
 *
 * 与邮件收件人各自独立：余额提醒会同时往「邮件收件人 + 这里配置的群 / 私聊」发。
 */
export interface QqRecipient {
  id: number
  scene: MailScene
  targetType: QqTargetType
  /** 群号或 QQ 号。 */
  targetId: number
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

/** 号池监控可选时间维度：短窗口（1h/6h/12h）用于分钟级排查，长窗口看趋势。 */
export type PoolRange = '90m' | '1h' | '6h' | '12h' | '1d' | '24h' | '7d' | '30d' | '90d'

/** 号池时序聚合粒度。minute 只在 ≤24 小时窗口内可用（依赖直连库秒级增量）。 */
export type PoolGranularity = 'minute' | 'hour' | 'day'

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

/** 号池直连库增量采集状态（用于判断分钟级数据是否可用）。 */
export interface PoolIngestStatus {
  enabled: boolean
  configured: boolean
  passwordConfigured: boolean
  lastUsageLogId: number
  lastRunAt: string | null
  latestSampleAt: string | null
  lagSeconds: number | null
}

/** 号池色块矩阵的一格（或整行汇总）指标。 */
export interface PoolHeatmapMetrics {
  requests: number
  cacheHitRate: number | null
  avgFirstTokenMs: number | null
  tokensPerSecond: number | null
  rpm: number | null
  actualCost: number
}

/** 色块矩阵的一行（一个号池账号）。 */
export interface PoolHeatmapRow {
  externalAccountId: number
  name: string | null
  platform: string | null
  boundKeyName: string | null
  boundKeyMasked: string | null
  total: PoolHeatmapMetrics
  cells: PoolHeatmapMetrics[]
}

/** 号池色块矩阵趋势：行 = 平台 / 账号，列 = 等宽时间桶。 */
export interface PoolHeatmap {
  from: string
  to: string
  granularity: PoolGranularity
  buckets: string[]
  summary: PoolHeatmapMetrics
  rows: PoolHeatmapRow[]
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
/**
 * 页面可配置的 QQ 机器人设置。
 *
 * 白名单等保存在数据库里，页面上改完立即生效，不需要重建容器；
 * 部署级接线参数（webhook 密钥、OneBot 地址、总开关）仍在环境变量里。
 */
export interface BotSettings {
  enabled: boolean
  allowedGroups: string[]
  allowedUsers: string[]
  /** 平台功能群（指定群），必须是 allowedGroups 的子集；为空表示任何群都不给平台功能。 */
  platformGroups: string[]
  requireMention: boolean
  commandPrefix: string
  maxReplyLength: number
  memoryWindow: number
  /** 是否允许非管理员（平台普通用户 / 陌生人）使用联网搜索。 */
  webSearchForNonAdmin: boolean
  /** 是否启用 Sub2API 错误突增主动告警。 */
  errorAlertEnabled: boolean
  errorAlertWindowMinutes: number
  errorAlertMinCount: number
  errorAlertSpikeRatio: number
  errorAlertCooldownMinutes: number
  /** MOSS-TTSD 默认音色，例如 fnlp/MOSS-TTSD-v0.5:alex。 */
  defaultVoice: string
  /** 默认语音语气/风格。 */
  defaultVoiceStyle: string
  updatedAt: string | null
}

/** 页面维护的一条机器人违禁词。 */
export interface BotForbiddenWord {
  id: number
  word: string
  createdAt: string
}

/** 每日消费抽奖设置。 */
export interface BotLotterySettings {
  enabled: boolean
  minUsageDaily: number
  payoutRatioPercent: number
  multiplierMin: number
  multiplierMax: number
  maxReward: number
  dailyBudget: number
  updatedAt: string | null
}

/** 一条抽奖记录。 */
export interface BotLotteryDraw {
  id: number
  email: string
  drawDate: string
  eligibleUsage: number
  payoutRatioPercent: number
  multiplier: number
  rewardAmount: number
  status: 'PENDING' | 'CREDITED' | 'FAILED' | 'NO_PRIZE'
  failureReason: string | null
  createdAt: string
  creditedAt: string | null
}

/** 监控项目侧登记的自定义管理员。 */
export interface BotAdmin {
  id: number
  email: string
  remark: string | null
  createdAt: string
}

/**
 * QQ 机器人身份识别总览。
 *
 * 管理员有两个来源，取并集：Sub2API 自带的 role=admin，以及这里自定义登记的名单。
 */
export interface BotIdentityOverview {
  enabled: boolean
  qqLocalPartMatch: boolean
  sub2Api: {
    available: boolean
    userCount: number
    adminCount: number
    syncedAt: string | null
  }
  customAdmins: BotAdmin[]
}
/** 一条机器人消息存档。 */
export interface BotMessage {
  id: number
  createdAt: string
  /** IN = 用户发给机器人，OUT = 机器人回复 */
  direction: 'IN' | 'OUT'
  messageType: string
  groupId: number | null
  userId: number
  senderEmail: string | null
  senderRole: 'ADMIN' | 'USER' | 'GUEST' | null
  persona: string | null
  content: string
  contentKind: 'TEXT' | 'IMAGE' | 'COMMAND' | 'VOICE'
  /** 同一问一答共用的关联 ID */
  correlationId: string | null
  messageId: string | null
}

/** 消息存档概况。 */
export interface BotMessageArchiveStats {
  enabled: boolean
  total: number
  earliest: string | null
  retentionDays: number
}

/** 机器人运行状态（「机器人状态」页）。进程内统计，重启后清零。 */
export interface BotStatus {
  enabled: boolean
  startedAt: string
  uptimeSeconds: number
  llm: {
    available: boolean
    model: string | null
    baseUrl: string | null
  }
  onebot: {
    configured: boolean
    reachable: boolean
    online: boolean
    baseUrl: string | null
    selfId: number | null
    nickname: string | null
    error: string | null
  }
  counters: {
    inbound: number
    replied: number
    dropped: number
    failed: number
    lastInboundAt: string | null
    lastOutboundAt: string | null
  }
  /** 正在处理的对话（谁、哪个群、说了什么） */
  activeTurns: Array<{
    at: string
    channel: string
    groupId: number | null
    userId: number | null
    text: string
  }>
  /** 最近完成的对话（问了什么、答了什么、耗时） */
  recentTurns: Array<{
    at: string
    channel: string
    groupId: number | null
    userId: number | null
    question: string
    answer: string
    elapsedMs: number
  }>
  /** 未回复原因 -> 次数 */
  dropReasons: Record<string, number>
  recentDrops: Array<{
    at: string
    channel: string
    groupId: number | null
    userId: number | null
    reason: string
  }>
  recentErrors: Array<{
    at: string
    userId: number | null
    message: string
  }>
  imageGen: {
    usable: boolean
    model: string | null
    maxPerMinute: number
  }
}

/** NapCat WebUI 的 QQ 登录状态。二维码 URL 仅在未登录时有意义。 */
export interface NapCatStatus {
  configured: boolean
  reachable: boolean
  login: boolean
  offline: boolean
  loginPhase: string
  qrCodeUrl: string | null
  loginError: string | null
  error: string | null
  checkedAt: string
}

/** 一行 NapCat 实时日志。 */
export interface NapCatLogLine {
  time: string
  level: string
  message: string
}

/** NapCat 实时日志快照。 */
export interface NapCatLogs {
  configured: boolean
  connected: boolean
  lines: NapCatLogLine[]
  updatedAt: string | null
  error: string | null
}
