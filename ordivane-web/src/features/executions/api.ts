import { executionWrite, HttpFailure, isRecord, requestResult } from '../../lib/http/client'
import { rawAt } from './raw-json'
import { actions, uuidPattern } from '../recovery-plans/api'
import type { Action } from '../recovery-plans/api'
import type { ExecutionIntent } from './intent'

/** 已接受十状态；文案不把消息确认解释为业务成功。 */
export const statusNames = { QUEUED: '排队中', DELIVERED_UNVERIFIED: '已投递，业务未确认', RETURNED: '消息退回', NACK: '消息未确认', UNKNOWN: '？结果待确认', BLOCKED: '执行受阻', WAITING: '等待复查', MANUAL_REQUIRED: '待人工处理', SUCCEEDED: '业务核验通过', CANCELLED: '已取消' }
/** 发送尝试不等于目标业务处理次数。 */
export interface SendAttempt { attemptId: string; fencingToken: number; outcome: string; startedAt: string; finishedAt: string | null }
/** 不可变业务观测，content保留服务器原始内容。 */
export interface Observation { id: string; verified: boolean; reason: string; observedAt: string; contentSha256: string; content: Record<string, unknown>; rawContent?: string }
/** 核验领取历史与对应观测分别标识。 */
export interface VerificationAttempt { attemptId: string; fencingToken: number; claimedAt: string; leaseUntil: string; observationId: string | null }
/** 完整执行投影；可空字段必须存在且显式为null。 */
export interface Execution {
  id: string; planId: string; requesterId: string; version: number; digest: string; eventId: string
  businessKey: string; fingerprint: string; transportFingerprint: string; status: keyof typeof statusNames
  eligibilityHeld: boolean; businessVerified: boolean; actionCompleted: boolean; action: Action; reason: string
  checks: Record<string, 'PASS' | 'NOT_APPLICABLE'>; createdAt: string; attempts: SendAttempt[]
  verificationCount: number; verificationLimit: number; nextCheckAt: string | null; verificationDeadline: string | null
  cancelRequestedAt: string | null; cancelledAt: string | null; releasedAt: string | null
  observations: Observation[]; verificationAttempts: VerificationAttempt[]
  timeline: { kind: string; recordedAt: string; detail: Record<string, unknown> }[]
  deliveryState: 'PENDING' | 'STARTED' | 'DONE' | 'BLOCKED' | 'CANCELLED' | null; incidentStatus: 'OPEN' | 'RESOLVED'
}
/** 不认识的响应不降级为成功或空记录。 */
function invalid(): never { throw new HttpFailure(0, 'INVALID_RESPONSE', '执行响应无效，请保留原意图查证') }
/** 各字段在HTTP信任边界严格读取。 */
function record(v: unknown): Record<string, unknown> { return isRecord(v) ? v : invalid() }
function text(v: Record<string, unknown>, k: string): string { return typeof v[k] === 'string' ? v[k] : invalid() }
function uuid(v: Record<string, unknown>, k: string): string { const s = text(v,k); return uuidPattern.test(s) ? s : invalid() }
function hash(v: Record<string, unknown>, k: string): string { const s = text(v,k); return /^[a-f0-9]{64}$/.test(s) ? s : invalid() }
function date(v: Record<string, unknown>, k: string): string { const s = text(v,k); return /(?:Z|[+-]\d\d:\d\d)$/.test(s) && Number.isFinite(Date.parse(s)) ? s : invalid() }
function nullableDate(v: Record<string, unknown>, k: string): string | null { return v[k] === null ? null : date(v,k) }
function bool(v: Record<string, unknown>, k: string): boolean { return typeof v[k] === 'boolean' ? v[k] : invalid() }
function integer(v: Record<string, unknown>, k: string, min = 0, max = Number.MAX_SAFE_INTEGER): number { const n = v[k]; return typeof n === 'number' && Number.isSafeInteger(n) && n >= min && n <= max ? n : invalid() }
function array(v: Record<string, unknown>, k: string): unknown[] { return Array.isArray(v[k]) ? v[k] : invalid() }
function choice<const T extends string>(v: unknown, choices: readonly T[]): T { return choices.find(c => c === v) ?? invalid() }
/** 校验真实执行ID和完整十状态，保留false/0/null。 */
export function executionFrom(value: unknown, id?: string): Execution {
  const v = record(value)
  const result: Execution = {
    id: uuid(v,'id'), planId: uuid(v,'planId'), requesterId: uuid(v,'requesterId'), version: integer(v,'version',1,2147483647), digest: hash(v,'digest'),
    eventId: uuid(v,'eventId'), businessKey: text(v,'businessKey'), fingerprint: text(v,'fingerprint'), transportFingerprint: text(v,'transportFingerprint'),
    status: choice(v.status, ['QUEUED','DELIVERED_UNVERIFIED','RETURNED','NACK','UNKNOWN','BLOCKED','WAITING','MANUAL_REQUIRED','SUCCEEDED','CANCELLED']),
    action: choice(v.action, actions.map(a => a.value)), eligibilityHeld: bool(v,'eligibilityHeld'), businessVerified: bool(v,'businessVerified'), actionCompleted: bool(v,'actionCompleted'), reason: text(v,'reason'),
    checks: Object.fromEntries(['CONFLICTING_EXECUTION','ATOMIC_EXECUTION_ELIGIBILITY'].map(k => [k, choice(record(v.checks)[k], ['PASS','NOT_APPLICABLE'])])),
    createdAt: date(v,'createdAt'), verificationCount: integer(v,'verificationCount'), verificationLimit: integer(v,'verificationLimit',1,20),
    nextCheckAt: nullableDate(v,'nextCheckAt'), verificationDeadline: nullableDate(v,'verificationDeadline'), cancelRequestedAt: nullableDate(v,'cancelRequestedAt'), cancelledAt: nullableDate(v,'cancelledAt'), releasedAt: nullableDate(v,'releasedAt'),
    deliveryState: v.deliveryState === null ? null : choice(v.deliveryState,['PENDING','STARTED','DONE','BLOCKED','CANCELLED']), incidentStatus: choice(v.incidentStatus,['OPEN','RESOLVED']),
    attempts: array(v,'attempts').map(item => { const a = record(item); return { attemptId: uuid(a,'attemptId'), fencingToken: integer(a,'fencingToken'), outcome: choice(a.outcome,['UNKNOWN','ACK','RETURNED','NACK','CONFIRM_TIMEOUT','CONNECTION_LOST']), startedAt: date(a,'startedAt'), finishedAt: nullableDate(a,'finishedAt') } }),
    observations: array(v,'observations').map(item => { const a = record(item); return { id: uuid(a,'id'), verified: bool(a,'verified'), reason: text(a,'reason'), observedAt: date(a,'observedAt'), contentSha256: hash(a,'contentSha256'), content: record(a.content) } }),
    verificationAttempts: array(v,'verificationAttempts').map(item => { const a = record(item); return { attemptId: uuid(a,'attemptId'), fencingToken: integer(a,'fencingToken'), claimedAt: date(a,'claimedAt'), leaseUntil: date(a,'leaseUntil'), observationId: a.observationId === null ? null : uuid(a,'observationId') } }),
    timeline: array(v,'timeline').map(item => { const a = record(item); return { kind: text(a,'kind'), recordedAt: date(a,'recordedAt'), detail: record(a.detail) } }),
  }
  if ((id !== undefined && result.id !== id) || (result.status === 'SUCCEEDED' && (!result.businessVerified || !result.actionCompleted))
    || (result.status === 'CANCELLED' && (result.businessVerified || result.eligibilityHeld || result.cancelledAt === null))) return invalid()
  return result
}
/** 已知ID只读回查，外部取消仅终止客户端等待。 */
export async function getExecution(id: string, signal?: AbortSignal): Promise<Execution> {
  const response = await requestResult(`/api/v1/executions/${encodeURIComponent(id)}`, { signal })
  const result = executionFrom(response.body, id)
  result.observations.forEach((item,index) => { item.rawContent = rawAt(response.raw,['observations',index,'content']) })
  return result
}
/** 用户明确确认同意图时才POST；响应绑定原身份及准确计划。 */
export async function createExecution(intent: ExecutionIntent): Promise<Execution> {
  const response = await executionWrite(`/api/v1/recovery-plans/${intent.planId}/executions`, { version: intent.version, digest: intent.digest }, intent.key)
  const execution = executionFrom(response.body)
  if (execution.planId !== intent.planId || execution.version !== intent.version || execution.digest !== intent.digest || execution.requesterId !== intent.identityId) return invalid()
  return execution
}
/** 无正文取消；202仅受理，最终状态由GET核验。 */
export async function cancelExecution(id: string, observationContext?: import('../../lib/diagnostics/read-task-observer').WriteTicket | null): Promise<{ status: number; execution: Execution }> {
  const response = await executionWrite(`/api/v1/executions/${encodeURIComponent(id)}/cancellations`, undefined, undefined, observationContext)
  if (response.status !== 200 && response.status !== 202) return invalid()
  return { status: response.status, execution: executionFrom(response.body,id) }
}
