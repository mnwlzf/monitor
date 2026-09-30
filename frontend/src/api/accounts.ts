import { apiRequest } from './client'
import type { Account, Platform, PlatformType } from '../types'

export interface CreateAccountInput {
  platformType: PlatformType
  platformName: string
  baseUrl: string
  displayName: string
  loginName: string
  password: string
}

interface InstanceDto {
  id: string | number
  name: string
  baseUrl: string
  platform: string
}

interface AccountDto {
  id: string | number
  displayName?: string
  loginName?: string
  authStatus?: string
}

export async function createAccountRecord(input: CreateAccountInput): Promise<Account> {
  const instance = await apiRequest<InstanceDto>('/api/v1/upstream/instances', {
    method: 'POST',
    body: JSON.stringify({
      name: input.platformName || `${input.platformType} - ${input.baseUrl}`,
      baseUrl: input.baseUrl,
      platform: input.platformType,
    }),
  })

  const account = await apiRequest<AccountDto>(
    `/api/v1/upstream/instances/${instance.id}/accounts`,
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

  return {
    id: Number(account.id),
    platformId: Number(instance.id),
    platformName: instance.name,
    platformType: input.platformType,
    displayName: account.displayName || input.displayName || input.loginName,
    loginName: account.loginName || input.loginName,
    balance: 0,
    frozenBalance: 0,
    quota: 0,
    usedQuota: 0,
    quotaUnit: 'USD',
    requestCount: 0,
    credentialStatus: 'VALID',
    lastCollectStatus: 'RUNNING',
    lastCollectedAt: null,
    nextCollectAt: null,
  }
}
export interface UpdateAccountInput {
  displayName: string
  loginName: string
  password: string
  authType?: string
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

  return {
    ...account,
    displayName: updated.displayName || input.displayName || account.displayName,
    loginName: updated.loginName || input.loginName || account.loginName,
    credentialStatus: updated.authStatus === 'INVALID' ? 'INVALID' : account.credentialStatus,
  }
}

export async function deleteAccountRecord(account: Account): Promise<void> {
  await apiRequest<void>(
    `/api/v1/upstream/instances/${account.platformId}/accounts/${account.id}`,
    { method: 'DELETE' },
  )
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
  return rows.map(row => ({
    id: Number(row.id),
    platformId: platform.id,
    platformName: platform.name,
    platformType: platform.type,
    displayName: row.displayName || row.loginName || `账号 ${row.id}`,
    loginName: row.loginName || '',
    balance: 0,
    frozenBalance: 0,
    quota: 0,
    usedQuota: 0,
    quotaUnit: 'USD',
    requestCount: 0,
    credentialStatus: row.authStatus === 'INVALID' ? 'INVALID' : 'UNKNOWN',
    lastCollectStatus: 'UNKNOWN',
    lastCollectedAt: null,
    nextCollectAt: null,
  }))
}