/** API 封装：自动带 Bearer token；401 清 token 跳登录（等价旧 common.js XJ.apiFetch） */

const TOKEN_KEY = 'xj_token'

export interface MenuInfo {
  code: string
  name: string
  path: string
}

export interface MeInfo {
  username: string
  displayName?: string
  isPlatformAdmin: boolean
  mustChangePassword?: boolean
  orgId?: number | null
  dataScope?: string
  menus: MenuInfo[]
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export async function apiFetch(path: string, opts: RequestInit = {}): Promise<Response> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((opts.headers as Record<string, string>) || {}),
  }
  const token = getToken()
  if (token) headers['Authorization'] = 'Bearer ' + token
  const r = await fetch(path, { ...opts, headers })
  if (r.status === 401) {
    clearToken()
    if (!location.hash.startsWith('#/login')) location.hash = '#/login'
    throw new Error('登录已失效，请重新登录')
  }
  return r
}

/** JSON 请求快捷方法；非 2xx 时抛出后端 error 信息 */
export async function apiJson<T = unknown>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const r = await apiFetch(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })
  const data = await r.json().catch(() => ({}))
  if (!r.ok) {
    const msg = (data as { error?: string }).error || (data as { message?: string }).message || `请求失败 (${r.status})`
    throw new Error(msg)
  }
  return data as T
}

export async function me(): Promise<MeInfo> {
  return apiJson<MeInfo>('/api/auth/me')
}

export async function logout(): Promise<void> {
  await apiFetch('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
  clearToken()
  location.hash = '#/login'
}
