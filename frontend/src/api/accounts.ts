import { apiRequest } from './client'
import type { Account, ChangeEvent, Channel, Platform, PlatformType, UsageDashboard } from '../types'

export interface CreateAccountInput {
  displayName: string
  loginName: string
  password: string
}

export interface UpdateAccountInput {
  displayName: string
  loginName: string
  password: string
  authType?: string
}

interface InstanceDto {
  id: string | number
  name: string
  baseUrl: string
  platform: string
}

interface AccountDto {
  id: string | number
  platformId?: string | number
  displayName?: string
  loginName?: string
  platformType?: string
  authStatus?: string
  status?: boolean
  balance?: number | null
  frozenBalance?: number | null
  quota?: number | null
  usedQuota?: number | null
  quotaUnit?: string | null
  requestCount?: number | null
  lastCollectStatus?: string | null
  lastCollectedAt?: string | null
  nextCollectAt?: string | null
}

/**
 * 在指定平台实例下新增采集账号。
 *
 * <p>平台与账号是一对多关系：账号始终挂在已有平台上，
 * 平台本身由「平台管理」维护，新增账号不会创建新的平台实例。</p>
 */
export async function createAccountRecord(platform: Platform, input: CreateAccountInput): Promise<Account> {
  const account = await apiRequest<AccountDto>(
    `/api/v1/upstream/instances/${platform.id}/accounts`,
    {
      method: 'POST',
      body: JSON.stringify({
        displayName: input.displayName || input.loginName,
        loginName: input.loginName,
        password: input.password,
        authType: 'PASSWORD',
      }),
    },
  )

  return mapAccount({
    id: platform.id,
    name: platform.name,
    baseUrl: platform.url,
    platform: platform.type,
  }, account, platform.type)
}

export async function updateAccountRecord(account: Account, input: UpdateAccountInput): Promise<Account> {
  const updated = await apiRequest<AccountDto>(
    `/api/v1/upstream/instances/${account.platformId}/accounts/${account.id}`,
    {
      method: 'PUT',
      body: JSON.stringify({
        displayName: input.displayName,
        loginName: input.loginName,
        password: input.password,
        authType: input.authType || 'PASSWORD',
      }),
    },
  )

  return mapAccount({
    id: account.platformId,
    name: account.platformName,
    baseUrl: '',
    platform: account.platformType,
  }, updated, account.platformType)
}

export async function deleteAccountRecord(account: Account): Promise<void> {
  await apiRequest<void>(
    `/api/v1/upstream/instances/${account.platformId}/accounts/${account.id}`,
    { method: 'DELETE' },
  )
}

export interface CreatePlatformInput {
  name: string
  baseUrl: string
  type: PlatformType
}

/**
 * 创建上游平台实例。平台与账号是一对多关系，账号在平台创建后单独添加。
 */
export async function createPlatformRecord(input: CreatePlatformInput): Promise<Platform> {
  const row = await apiRequest<InstanceDto>('/api/v1/upstream/instances', {
    method: 'POST',
    body: JSON.stringify({
      name: input.name || `${input.type} - ${input.baseUrl}`,
      baseUrl: input.baseUrl,
      platform: input.type,
    }),
  })

  return {
    id: Number(row.id),
    name: row.name,
    type: (row.platform as PlatformType) || input.type,
    url: row.baseUrl,
    status: true,
    accountCount: 0,
    lastCollectedAt: null,
  }
}

export interface UpdatePlatformInput {
  name: string
  baseUrl: string
  type: PlatformType
}

/**
 * 更新平台实例。平台类型/URL 变更后，其下所有账号按新配置采集。
 */
export async function updatePlatformRecord(platform: Platform, input: UpdatePlatformInput): Promise<Platform> {
  const row = await apiRequest<InstanceDto>(`/api/v1/upstream/instances/${platform.id}`, {
    method: 'PUT',
    body: JSON.stringify({
      name: input.name,
      baseUrl: input.baseUrl,
      platform: input.type,
    }),
  })

  return {
    id: Number(row.id ?? platform.id),
    name: row.name ?? input.name,
    type: (row.platform as PlatformType) || input.type,
    url: row.baseUrl ?? input.baseUrl,
    status: true,
    accountCount: platform.accountCount,
    lastCollectedAt: platform.lastCollectedAt,
  }
}

/**
 * 删除平台实例。平台下仍有账号时后端会拒绝，需先删除账号。
 */
export async function deletePlatformRecord(platform: Platform): Promise<void> {
  await apiRequest<void>(`/api/v1/upstream/instances/${platform.id}`, { method: 'DELETE' })
}

export async function listPlatformRecords(): Promise<Platform[]> {
  const rows = await apiRequest<InstanceDto[]>('/api/v1/upstream/instances')
  return rows.map(row => ({
    id: Number(row.id),
    name: row.name,
    type: row.platform as PlatformType,
    url: row.baseUrl,
    status: true,
    accountCount: 0,
    lastCollectedAt: null,
  }))
}

export async function listAccountRecords(platform: Platform): Promise<Account[]> {
  const rows = await apiRequest<AccountDto[]>(`/api/v1/upstream/instances/${platform.id}/accounts`)
  return rows.map(row => mapAccount({
    id: platform.id,
    name: platform.name,
    baseUrl: platform.url,
    platform: platform.type,
  }, row, platform.type))
}

export async function collectAccountRecord(account: Account): Promise<void> {
  await apiRequest<void>(
    `/api/v1/upstream/instances/${account.platformId}/accounts/${account.id}/collect`,
    { method: 'POST' },
  )
}

function mapAccount(instance: InstanceDto, row: AccountDto, fallbackType: PlatformType): Account {
  return {
    id: Number(row.id),
    platformId: Number(row.platformId ?? instance.id),
    platformName: instance.name,
    platformType: (row.platformType as PlatformType) || fallbackType,
    displayName: row.displayName || row.loginName || `账号 ${row.id}`,
    loginName: row.loginName || '',
    balance: Number(row.balance ?? 0),
    frozenBalance: Number(row.frozenBalance ?? 0),
    quota: Number(row.quota ?? 0),
    usedQuota: Number(row.usedQuota ?? 0),
    quotaUnit: row.quotaUnit || 'USD',
    requestCount: Number(row.requestCount ?? 0),
    credentialStatus: normalizeCredentialStatus(row.authStatus),
    lastCollectStatus: normalizeCollectStatus(row.lastCollectStatus),
    lastCollectedAt: row.lastCollectedAt || null,
    nextCollectAt: row.nextCollectAt || null,
  }
}

function normalizeCredentialStatus(status?: string): Account['credentialStatus'] {
  if (status === 'VALID' || status === 'INVALID') return status
  return 'UNKNOWN'
}

function normalizeCollectStatus(status?: string | null): Account['lastCollectStatus'] {
  if (status === 'SUCCESS' || status === 'FAILED' || status === 'RUNNING' || status === 'PARTIAL') return status
  return 'UNKNOWN'
}

interface GroupDto {
  id: string | number
  accountId: string | number
  externalGroupId: string
  groupName: string
  description?: string
  platform?: string
  currentRatio?: number | null
  currentBaseRatio?: number | null
  status?: string
  active?: boolean
  lastSeenAt?: string | null
}

interface ChangeEventDto {
  id: string | number
  changeType: string
  entityKey?: string
  fieldName?: string
  oldValue?: string
  newValue?: string
  severity?: string
  message?: string
  detectedAt?: string
}

export async function listGroupRecords(platform: Platform, accountMap: Map<number, Account>): Promise<Channel[]> {
  const rows = await apiRequest<GroupDto[]>(`/api/v1/upstream/instances/${platform.id}/groups`)
  return rows.map(row => ({
    id: String(row.id),
    name: row.groupName,
    platformId: platform.id,
    platformName: platform.name,
    platformType: platform.type,
    platform: row.platform || '',
    ratio: Number(row.currentRatio ?? 0),
    baseRatio: row.currentBaseRatio == null ? null : Number(row.currentBaseRatio),
    status: row.status || (row.active ? 'active' : 'inactive'),
    accountName: accountMap.get(Number(row.accountId))?.displayName || '未知账号',
  }))
}

export async function listChangeRecords(platform: Platform): Promise<ChangeEvent[]> {
  const rows = await apiRequest<ChangeEventDto[]>(`/api/v1/upstream/instances/${platform.id}/changes?limit=100`)
  return rows.map(row => ({
    id: String(row.id),
    type: row.changeType,
    entity: row.entityKey || '',
    field: row.fieldName || '',
    oldValue: row.oldValue || 'null',
    newValue: row.newValue || 'null',
    severity: row.severity === 'WARNING' || row.severity === 'CRITICAL' ? row.severity : 'INFO',
    detectedAt: row.detectedAt || new Date().toISOString(),
    message: row.message || '',
  }))
}
interface UsageDashboardDto {
  id: string | number
  accountId: string | number
  platformId?: string | number
  displayName?: string
  platformType?: string
  balance?: number | null
  frozenBalance?: number | null
  totalRequests?: number | null
  totalTokens?: number | null
  totalCost?: number | null
  totalActualCost?: number | null
  metrics?: Record<string, unknown> | null
  platformStats?: Array<Record<string, unknown>> | null
  collectedAt?: string | null
}

/**
 * 读取平台下各账号的最新用量看板快照。
 *
 * newapi 与 sub2api 共用同一接口与结构，缺失指标保持 null 交由界面降级展示。
 */
export async function listUsageDashboardRecords(platform: Platform): Promise<UsageDashboard[]> {
  const rows = await apiRequest<UsageDashboardDto[]>(
    `/api/v1/upstream/instances/${platform.id}/usage-dashboard`,
  )
  return rows.map(row => ({
    id: Number(row.id),
    accountId: Number(row.accountId),
    platformId: Number(row.platformId ?? platform.id),
    displayName: row.displayName || `账号 ${row.accountId}`,
    platformType: (row.platformType as PlatformType) || platform.type,
    balance: toNullableNumber(row.balance),
    frozenBalance: toNullableNumber(row.frozenBalance),
    totalRequests: toNullableNumber(row.totalRequests),
    totalTokens: toNullableNumber(row.totalTokens),
    totalCost: toNullableNumber(row.totalCost),
    totalActualCost: toNullableNumber(row.totalActualCost),
    metrics: row.metrics ?? {},
    platformStats: row.platformStats ?? [],
    collectedAt: row.collectedAt || null,
  }))
}

/**
 * 用量指标允许为空，缺失时保持 null，避免用 0 掩盖「平台未提供」。
 */
function toNullableNumber(value: number | null | undefined): number | null {
  return value == null ? null : Number(value)
}
