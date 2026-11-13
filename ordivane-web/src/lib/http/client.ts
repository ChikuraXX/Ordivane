import { getReadObserver } from '../diagnostics/read-task-observer'
import type { WriteTicket } from '../diagnostics/read-task-observer'

/** 服务端安全错误；不记录请求体、Cookie 或 CSRF。 */
export class HttpFailure extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly traceId = '',
  ) { super(message) }
}

/** 当前会话 CSRF，仅保存在内存，认证边界变化时清除。 */
export interface Csrf { headerName: string; token: string }
let csrf: Csrf | null = null
/** 认证边界代际，迟到旧请求不能使新登录失效。 */
let sessionGeneration = 0
let onUnauthorized: (() => void) | undefined

/** 注册会话失效边界；登录失败由登录表单自行反馈。 */
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler }
/** 清除失效的 CSRF，不读取 HttpOnly 会话 Cookie。 */
export function clearCsrf() { csrf = null; sessionGeneration++ }

/** 将未知 JSON 缩窄为可检查对象。 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 统一同源请求；不重试写操作，超时也不推定操作成功。 */
export async function request(path: string, init: RequestInit = {}, handleUnauthorized = true): Promise<unknown> {
  return (await requestResult(path, init, handleUnauthorized)).body
}

/** 保留HTTP状态供取消受理判定；组合调用方取消与内部超时。 */
export async function requestResult(path: string, init: RequestInit = {}, handleUnauthorized = true, observationContext?: WriteTicket | null): Promise<{ status: number; body: unknown; raw: string }> {
  if (!path.startsWith('/api/') || path.startsWith('//')) throw new Error('仅允许同源 API')
  const requestGeneration = sessionGeneration
  const controller = new AbortController()
  const observer = getReadObserver()
  const observed = observer?.http(path, init, controller.signal, observationContext) ?? null
  const cancel = () => { observer?.httpPhase(observed, 'EXTERNAL_CANCEL'); controller.abort() }
  if (init.signal?.aborted) cancel()
  else init.signal?.addEventListener('abort', cancel, { once: true })
  const timeout = window.setTimeout(() => { observer?.httpPhase(observed, 'TIMEOUT'); controller.abort() }, 15_000)
  try {
    observer?.httpPhase(observed, 'BEFORE_FETCH')
    const headers = observed ? observer?.headers(init.headers, observed) : init.headers
    const response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    observer?.httpPhase(observed, 'HEADERS', response.status)
    let body: unknown = null
    let raw = ''
    if (response.status !== 204) {
      try { raw = await response.text(); body = JSON.parse(raw) } catch { /* 非 JSON 的代理错误仍按 HTTP 状态反馈。 */ }
    }
    observer?.httpPhase(observed, 'BODY', response.status)
    if (init.signal?.aborted) throw new DOMException('查询已取消', 'AbortError')
    if (!response.ok) {
      const code = isRecord(body) && typeof body.code === 'string' ? body.code : 'HTTP_ERROR'
      const traceId = isRecord(body) && typeof body.traceId === 'string' ? body.traceId : ''
      if (response.status === 401 && handleUnauthorized && requestGeneration === sessionGeneration) { observer?.httpPhase(observed, 'AUTH', response.status); onUnauthorized?.() }
      throw new HttpFailure(response.status, code, failureMessage(response.status, code), traceId)
    }
    return { status: response.status, body, raw }
  } catch (error) {
    if (init.signal?.aborted && (!init.method || init.method === 'GET')) throw new DOMException('查询已取消', 'AbortError')
    if (error instanceof HttpFailure) throw error
    throw new HttpFailure(0, 'NETWORK_ERROR', '连接失败或请求超时，请重试；操作结果尚未确认')
  } finally { window.clearTimeout(timeout); init.signal?.removeEventListener('abort', cancel); observer?.httpPhase(observed, 'FINALLY') }
}

/** 按稳定错误码显示安全的简短反馈，不回显不可信错误正文。 */
export function failureMessage(status: number, code: string): string {
  if (code === 'CSRF_REJECTED') return '会话校验失败，请重试'
  if (status === 401) return '会话已失效，请重新登录'
  if (status === 403) return '当前身份无权执行此操作'
  if (status === 404) return '资源不存在或不可访问'
  return '服务暂不可用，请重试'
}

/** 每次认证成功后必须重新获取，禁止复用登录前 token。 */
export async function refreshCsrf(): Promise<void> {
  csrf = null
  const value = await request('/api/v1/access/csrf', {}, false)
  if (!isRecord(value) || value.headerName !== 'X-CSRF-TOKEN' || typeof value.token !== 'string' || !value.token) {
    throw new HttpFailure(0, 'INVALID_RESPONSE', '会话响应无效，请重试')
  }
  csrf = { headerName: value.headerName, token: value.token }
}

/** 会话写请求携带当前 CSRF；不自动补发被拒绝的请求。 */
export async function sessionWrite(path: string, body?: URLSearchParams, handleUnauthorized = true): Promise<void> {
  if (!csrf) await refreshCsrf()
  const current = csrf
  if (!current) throw new HttpFailure(0, 'INVALID_RESPONSE', '会话校验不可用，请重试')
  const headers: Record<string, string> = { [current.headerName]: current.token }
  if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'
  await request(path, { method: 'POST', headers, body }, handleUnauthorized)
}

/** 计划 JSON 写入只发送一次；失败后由调用方查询已知对象核验结果。 */
export async function sessionJson(path: string, body: object): Promise<unknown> {
  if (!csrf) await refreshCsrf()
  const current = csrf
  if (!current) throw new HttpFailure(0, 'INVALID_RESPONSE', '会话校验不可用，请重新登录')
  return request(path, { method: 'POST', headers: {
    [current.headerName]: current.token, 'Content-Type': 'application/json',
  }, body: JSON.stringify(body) })
}

/** 执行写请求仅一次；无正文取消不发送Content-Type，状态码和正文分别保留。 */
export async function executionWrite(path: string, body?: { version: number; digest: string }, key?: string, observationContext?: WriteTicket | null): Promise<{ status: number; body: unknown }> {
  if (!csrf) await refreshCsrf()
  const current = csrf
  if (!current) throw new HttpFailure(0, 'INVALID_RESPONSE', '会话校验不可用，请重新登录')
  const headers: Record<string, string> = { [current.headerName]: current.token }
  if (key) headers['Idempotency-Key'] = key
  if (body) headers['Content-Type'] = 'application/json'
  return requestResult(path, { method: 'POST', headers, ...(body ? { body: JSON.stringify(body) } : {}) }, true, observationContext)
}
