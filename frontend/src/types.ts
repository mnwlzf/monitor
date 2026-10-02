export type PlatformType = 'newapi' | 'sub2api'

export interface Platform {
  id: number
  name: string
  type: PlatformType
  url: string
  status: boolean
  accountCount: number
  lastCollectedAt: string | null
}

export interface Account {
  id: number
  platformId: number
  platformName: string
  platformType: PlatformType
  displayName: string
  loginName: string
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
  entity: string
  field: string
  oldValue: string
  newValue: string
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  detectedAt: string
  message: string
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