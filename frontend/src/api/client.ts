type ApiEnvelope<T> = { data: T; message?: string }

export async function apiRequest<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')

  const response = await fetch(url, { ...init, headers, credentials: 'include' })
  const body = await response.json().catch(() => null) as ApiEnvelope<T> | null
  if (!response.ok) {
    throw new Error(body?.message || `请求失败 (${response.status})`)
  }
  return body?.data as T
}