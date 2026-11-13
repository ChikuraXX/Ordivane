import { HttpFailure, isRecord, request, sessionWrite } from '../../lib/http/client'

/** 已接受案件投影；建案时间不是事件发生时间。 */
export interface Incident {
  id: string; source: string; event_id: string; handler_id: string; status: 'OPEN' | 'RESOLVED'; created_at: string
  resolved_at: string | null; resolved_execution_id: string | null; resolution_observation_id: string | null
}
/** 独立失败观察；空正文和缺失报告标识保留原语义。 */
export interface Observation {
  id: string; report_id: string | null; observation_id: string | null; kind: string
  body: string | null; body_sha256: string; event_conflict: boolean; received_at: string
}
/** 案件详情与首次接收后不可变的原始事件。 */
export interface IncidentDetail extends Incident {
  original_event: string; event_sha256: string; observations: Observation[]
}
/** 服务端稳定排序的分页结果，页码从零开始。 */
export interface IncidentPage { items: Incident[]; total: number; page: number; size: number }
/** 每次同步采集追加的不可变快照。 */
export interface Evidence {
  id: string; incident_id: string; source: string; collected_at: string
  outcome: string; content: string; content_sha256: string
}
/** 当前只消费契约支持的精确筛选。 */
export interface Filters { page: number; size: number; source: string; status: string }

/** 响应结构异常不降级为空结果或成功。 */
function invalid(): never { throw new HttpFailure(0, 'INVALID_RESPONSE', '案件响应无效，请重试') }
/** 从未知响应读取必需字符串，不将 null/false 转成文本。 */
function textField(value: Record<string, unknown>, key: string): string {
  const field = value[key]
  return typeof field === 'string' ? field : invalid()
}
/** nullable 标识保留 null，不采用空串回退。 */
function nullableField(value: Record<string, unknown>, key: string): string | null {
  return value[key] === null ? null : textField(value, key)
}
/** 解决依据按契约校验UUID或带时区时间，不接受空串或伪关联。 */
function resolutionField(value: Record<string, unknown>, key: string, timestamp = false): string | null {
  const field = nullableField(value,key)
  if (field === null) return null
  return (timestamp ? /(?:Z|[+-]\d\d:\d\d)$/.test(field) && Number.isFinite(Date.parse(field))
    : /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(field)) ? field : invalid()
}
/** 仅在信任边界校验案件投影。 */
export function incidentFrom(value: unknown): Incident {
  if (!isRecord(value) || (value.status !== 'OPEN' && value.status !== 'RESOLVED')) return invalid()
  return { id: textField(value, 'id'), source: textField(value, 'source'), event_id: textField(value, 'event_id'),
    handler_id: textField(value, 'handler_id'), status: value.status, created_at: textField(value, 'created_at'),
    resolved_at: resolutionField(value,'resolved_at',true), resolved_execution_id: resolutionField(value,'resolved_execution_id'), resolution_observation_id: resolutionField(value,'resolution_observation_id') }
}
/** 查询真实分页，不客户端重排或逐行补详情。 */
export async function listIncidents(filters: Filters): Promise<IncidentPage> {
  const query = new URLSearchParams({ page: String(filters.page), size: String(filters.size) })
  if (filters.source) query.set('source', filters.source)
  if (filters.status) query.set('status', filters.status)
  const value = await request(`/api/v1/incidents?${query}`)
  if (!isRecord(value) || !Array.isArray(value.items) || typeof value.total !== 'number'
    || !Number.isSafeInteger(value.total) || value.total < 0 || value.page !== filters.page || value.size !== filters.size) return invalid()
  return { items: value.items.map(incidentFrom), total: value.total, page: filters.page, size: filters.size }
}
/** 读取详情与观察；响应必须关联当前请求的案件。 */
export async function getIncident(id: string, signal?: AbortSignal): Promise<IncidentDetail> {
  const value = await request(`/api/v1/incidents/${encodeURIComponent(id)}`, { signal })
  if (!isRecord(value) || value.id !== id || !Array.isArray(value.observations)) return invalid()
  const observations = value.observations.map((entry: unknown): Observation => {
    if (!isRecord(entry) || typeof entry.event_conflict !== 'boolean') return invalid()
    return { id: textField(entry, 'id'), report_id: nullableField(entry, 'report_id'),
      observation_id: nullableField(entry, 'observation_id'), kind: textField(entry, 'kind'),
      body: nullableField(entry, 'body'), body_sha256: textField(entry, 'body_sha256'),
      event_conflict: entry.event_conflict, received_at: textField(entry, 'received_at') }
  })
  return { ...incidentFrom(value), original_event: textField(value, 'original_event'), event_sha256: textField(value, 'event_sha256'), observations }
}
/** 读取全部历史，不接受跨案件快照。 */
export async function getEvidence(id: string): Promise<Evidence[]> {
  const value = await request(`/api/v1/incidents/${encodeURIComponent(id)}/evidence`)
  if (!Array.isArray(value)) return invalid()
  return value.map((entry: unknown): Evidence => {
    if (!isRecord(entry) || entry.incident_id !== id) return invalid()
    return { id: textField(entry, 'id'), incident_id: id, source: textField(entry, 'source'),
      collected_at: textField(entry, 'collected_at'), outcome: textField(entry, 'outcome'),
      content: textField(entry, 'content'), content_sha256: textField(entry, 'content_sha256') }
  })
}
/** 无正文同步 POST；身份/CSRF复用既有边界，不自动重放。 */
export async function collectEvidence(id: string): Promise<void> {
  await sessionWrite(`/api/v1/incidents/${encodeURIComponent(id)}/evidence-collections`)
}
/** 解析规范 JSON 仅用于读取字段；展示正文保留原字符串及精确数值。 */
export function jsonObject(text: string | null): Record<string, unknown> | null {
  if (text === null) return null
  try { const value: unknown = JSON.parse(text); return isRecord(value) ? value : null } catch { return null }
}
/** 显式区分未提供、null、空串与false。复杂对象在原始JSON中完整展示。 */
export function scalar(value: unknown): string {
  if (value === undefined) return '未提供'
  if (value === null) return '无值'
  if (value === '') return '空字符串'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  return '见完整 JSON'
}
/** 浏览器时区日期加显式UTC偏移，原始时间由 time 的 title 保留。 */
export function dateTime(value: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return value
  const offset = -date.getTimezoneOffset()
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} UTC${offset >= 0 ? '+' : '-'}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`
}
