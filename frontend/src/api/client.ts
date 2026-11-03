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

export interface ApiRequestOptions {
  /** 可选超时时间；仅初始化等不能长期占用的请求使用。 */
  timeoutMs?: number
}

export async function apiRequest<T>(
  url: string,
  init: RequestInit = {},
  options: ApiRequestOptions = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')

  const controller = options.timeoutMs ? new AbortController() : null
  const timeoutId = controller
    ? setTimeout(() => controller.abort(), options.timeoutMs)
    : undefined
  const signal = init.signal ?? controller?.signal

  let response: Response
  try {
    response = await fetch(url, { ...init, headers, credentials: 'include', signal })
  } catch (error) {
    if (controller?.signal.aborted) {
      throw new ApiError('请求超时，请稍后重试', 408, 'REQUEST_TIMEOUT')
    }
    throw error
  } finally {
    if (timeoutId) clearTimeout(timeoutId)
  }

  const body = await response.json().catch(() => null) as (ApiEnvelope<T> & { code?: string }) | null
  if (!response.ok) {
    throw new ApiError(body?.message || `请求失败 (${response.status})`, response.status, body?.code)
  }
  return body?.data as T
}
