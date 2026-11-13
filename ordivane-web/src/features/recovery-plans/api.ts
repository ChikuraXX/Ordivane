import { HttpFailure, isRecord, request, sessionJson } from '../../lib/http/client'

/** 四个冻结动作；无默认重放，等待参数单位为秒。 */
export const actions = [
  { value: 'REPLAY_ORIGINAL', label: '原事件重放', effect: '保留原始载荷，申请受控重放。' },
  { value: 'VERIFY_ONLY', label: '只读核验', effect: '查询当前业务事实，不发布恢复消息。' },
  { value: 'WAIT_AND_RECHECK', label: '等待复查', effect: '申请等待后复查，不在浏览器自动重放。' },
  { value: 'MANUAL_INTERVENTION', label: '转人工', effect: '交人工处理，不发布恢复消息。' },
] as const
/** 对外动作枚举以BE-050机器契约为准。 */
export type Action = typeof actions[number]['value']
/** 准确版本和摘要由后端生成，原样回传。 */
export interface Exact { version: number; digest: string }
/** 新版创建所需的已知前版，不推测最新ID。 */
export interface Previous extends Exact { id: string }
/** 创建请求只包含契约字段，证据为同案成功采集ID。 */
export interface CreatePlan {
  action: Action; parameters: { delaySeconds?: number }; evidenceIds: string[]; previous: Previous | null
}
/** 事实保留为只读JSON；预检不授予执行资格。 */
export interface Precheck extends Exact {
  id: string; planId: string; configurationDigest: string; createdAt: string; expiresAt: string
  passed: boolean; reasons: string[]; facts: Record<string, unknown>; businessVerified: boolean
  executionAuthorized: false; deferredChecks: string[]
}
/** 审批投影沿用契约的snake_case，无意见字段。 */
export interface Decision { approver_id: string; decision: 'APPROVE' | 'REJECT'; digest: string; created_at: string }
/** 不可变计划及历史；派生status不能覆盖原始状态。 */
export interface Plan extends Previous {
  incidentId: string; previousId: string | null; requesterId: string; action: Action
  parameters: { delaySeconds?: number }; evidenceIds: string[]; configurationDigest: string
  eventDigest: string; actionDigest: string; createdAt: string; expiresAt: string
  recordedStatus: string; status: string; executionAuthorized: false; decisions: Decision[]
  approvalRequests: { precheck_id: string; created_at: string }[]; prechecks: Precheck[]
}

/** 信任边界异常不能降级为草稿或成功。 */
function invalid(): never { throw new HttpFailure(0, 'INVALID_RESPONSE', '计划响应无效，请查询核验') }
/** UUID用于对象归属，摘要不能转数值或截断。 */
export const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** 必需字符串保留准确值。 */
function field(o: Record<string, unknown>, key: string): string { return typeof o[key] === 'string' ? o[key] : invalid() }
/** 标识及摘要在响应入口校验。 */
function uuid(o: Record<string, unknown>, key: string): string { const s = field(o, key); return uuidPattern.test(s) ? s : invalid() }
/** SHA-256由服务器提供。 */
function hash(o: Record<string, unknown>, key: string): string { const s = field(o, key); return /^[a-f0-9]{64}$/.test(s) ? s : invalid() }
/** 时间必须可解析，不把无效期限当永久有效。 */
function date(o: Record<string, unknown>, key: string): string { const s = field(o, key); return Number.isFinite(Date.parse(s)) ? s : invalid() }
/** 布尔值不强制转换，false不当缺失。 */
function bool(o: Record<string, unknown>, key: string): boolean { return typeof o[key] === 'boolean' ? o[key] : invalid() }
/** 数组投影不回退为空。 */
function array(o: Record<string, unknown>, key: string): unknown[] { return Array.isArray(o[key]) ? o[key] : invalid() }
/** 原因与延期检查保留未知值供只读审查。 */
function strings(o: Record<string, unknown>, key: string): string[] {
  return array(o, key).map(v => typeof v === 'string' ? v : invalid())
}
/** 正整数版本不接受字符串、零或小数。 */
function version(o: Record<string, unknown>): number {
  const v = o.version; return typeof v === 'number' && Number.isSafeInteger(v) && v > 0 && v <= 2147483647 ? v : invalid()
}
/** 四动作参数按冻结契约校验，不提供任意JSON入口。 */
function parameters(action: Action, value: unknown): { delaySeconds?: number } {
  if (!isRecord(value)) return invalid()
  if (action !== 'WAIT_AND_RECHECK') return Object.keys(value).length === 0 ? {} : invalid()
  const delay = value.delaySeconds
  return Object.keys(value).length === 1 && typeof delay === 'number' && Number.isInteger(delay) && delay >= 1 && delay <= 3600
    ? { delaySeconds: delay } : invalid()
}
/** 预检必须属于当前准确版本，不能串案或串摘要。 */
export function precheckFrom(value: unknown, plan: Previous & { configurationDigest: string }): Precheck {
  if (!isRecord(value) || value.planId !== plan.id || value.version !== plan.version || value.digest !== plan.digest
    || value.configurationDigest !== plan.configurationDigest || value.executionAuthorized !== false || !isRecord(value.facts)) return invalid()
  return { id: uuid(value, 'id'), planId: plan.id, version: version(value), digest: hash(value, 'digest'),
    configurationDigest: hash(value, 'configurationDigest'), createdAt: date(value, 'createdAt'), expiresAt: date(value, 'expiresAt'),
    passed: bool(value, 'passed'), businessVerified: bool(value, 'businessVerified'), reasons: strings(value, 'reasons'),
    facts: value.facts, executionAuthorized: false, deferredChecks: strings(value, 'deferredChecks') }
}
/** 响应必须匹配已请求的案件和计划，未知状态仅只读。 */
export function planFrom(value: unknown, incidentId: string, planId?: string): Plan {
  if (!isRecord(value) || value.incidentId !== incidentId || (planId !== undefined && value.id !== planId)
    || value.executionAuthorized !== false) return invalid()
  const action = actions.find(a => a.value === value.action)?.value
  if (!action) return invalid()
  const base = { id: uuid(value, 'id'), version: version(value), digest: hash(value, 'digest'), configurationDigest: hash(value, 'configurationDigest') }
  const evidenceIds = strings(value, 'evidenceIds')
  if (evidenceIds.length < 1 || evidenceIds.length > 32 || new Set(evidenceIds).size !== evidenceIds.length || evidenceIds.some(id => !uuidPattern.test(id))) return invalid()
  return { ...base, incidentId, action, parameters: parameters(action, value.parameters), evidenceIds,
    previousId: value.previousId === null ? null : uuid(value, 'previousId'), requesterId: uuid(value, 'requesterId'),
    eventDigest: hash(value, 'eventDigest'), actionDigest: hash(value, 'actionDigest'),
    createdAt: date(value, 'createdAt'), expiresAt: date(value, 'expiresAt'), recordedStatus: field(value, 'recordedStatus'), status: field(value, 'status'),
    executionAuthorized: false, prechecks: array(value, 'prechecks').map(v => precheckFrom(v, base)),
    approvalRequests: array(value, 'approvalRequests').map(v => {
      if (!isRecord(v)) return invalid()
      return { precheck_id: uuid(v, 'precheck_id'), created_at: date(v, 'created_at') }
    }),
    decisions: array(value, 'decisions').map(v => {
      if (!isRecord(v) || (v.decision !== 'APPROVE' && v.decision !== 'REJECT')) return invalid()
      return { approver_id: uuid(v, 'approver_id'), decision: v.decision, digest: hash(v, 'digest'), created_at: date(v, 'created_at') }
    }),
  }
}
/** 查询准确ID，不查询不存在的列表或最新版本端点。 */
export async function getPlan(incidentId: string, id: string, signal?: AbortSignal): Promise<Plan> {
  return planFrom(await request(`/api/v1/recovery-plans/${encodeURIComponent(id)}`, { signal }), incidentId, id)
}
/** 创建响应的真实ID是唯一跳转依据。 */
export async function createPlan(incidentId: string, body: CreatePlan): Promise<Plan> {
  return planFrom(await sessionJson(`/api/v1/incidents/${encodeURIComponent(incidentId)}/recovery-plans`, body), incidentId)
}
/** 版本和摘要必须来自当前已加载计划。 */
export function exact(plan: Exact): Exact { return { version: plan.version, digest: plan.digest } }
/** 同步预检；503后调用者GET历史，不自动重发POST。 */
export async function precheck(plan: Plan): Promise<Precheck> {
  return precheckFrom(await sessionJson(`/api/v1/recovery-plans/${plan.id}/prechecks`, exact(plan)), plan)
}
/** 提交准确版本及最新有效预检。 */
export async function submitApproval(plan: Plan, precheckId: string): Promise<Plan> {
  return planFrom(await sessionJson(`/api/v1/recovery-plans/${plan.id}/approval-requests`, { ...exact(plan), precheckId }), plan.incidentId, plan.id)
}
/** 他人审批；后端仍执行持久身份、自审及并发校验。 */
export async function decide(plan: Plan, decision: 'APPROVE' | 'REJECT'): Promise<Plan> {
  return planFrom(await sessionJson(`/api/v1/recovery-plans/${plan.id}/approval-decisions`, { ...exact(plan), decision }), plan.incidentId, plan.id)
}
