type ApiEnvelope<T> = { data: T; message?: string }

/**
 * 带 HTTP 状态码的接口错误，便于区分 401/403。
 */
export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 是否为未登录（401）。 */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

/** 是否为权限不足（403）。 */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403
}

export async function apiRequest<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')

  const response = await fetch(url, { ...init, headers, credentials: 'include' })
  const body = await response.json().catch(() => null) as (ApiEnvelope<T> & { code?: string }) | null
  if (!response.ok) {
    throw new ApiError(body?.message || `请求失败 (${response.status})`, response.status, body?.code)
  }
  return body?.data as T
}