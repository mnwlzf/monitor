import { apiRequest } from './client'
import type { Account, PlatformType } from '../types'

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