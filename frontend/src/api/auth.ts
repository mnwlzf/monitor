import { apiRequest } from './client'
import type { CurrentUser } from '../types'

/** 登录并建立会话。 */
export async function login(username: string, password: string): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}

/** 退出登录。 */
export async function logout(): Promise<void> {
  await apiRequest<void>('/api/v1/auth/logout', { method: 'POST' })
}

/** 获取当前登录用户；未登录时接口返回 401。 */
export async function fetchCurrentUser(): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/api/v1/auth/me')
}