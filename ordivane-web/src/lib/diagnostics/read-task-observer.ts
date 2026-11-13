/** H2局部只读观测；默认关闭，不参与业务准入、权限或重试。 */
export type ObservationState = 'ACTIVE' | 'RECORD_ONLY' | 'SEALED' | 'FAILED'
export type ObservationCause = 'INITIAL' | 'VISIBILITY' | 'TIMER' | 'MANUAL' | 'IDENTITY_OR_ROUTE' | 'WRITE_RECHECK'
export type CloseReason = 'ROUTE' | 'UNMOUNT'
export type RetireReason = 'HIDDEN' | 'OFFLINE' | 'ROUTE' | 'IDENTITY' | 'UNMOUNT' | 'WRITE_PREP'
export type GuardStage = 'RESULT' | 'ERROR' | 'FINALLY' | 'FOCUS' | 'SCHEDULE'
export type Effect = 'DATA' | 'ERROR' | 'NOTICE' | 'BUSY' | 'LOADING' | 'FOCUS' | 'TIMER' | 'STOP' | 'AUTH'
type Value = string | number | boolean | null | readonly string[]
/** 只允许安全标量；不序列化Signal、Promise、headers、body或错误堆栈。 */
export interface ObservationEvent {
  readonly version: 3; readonly documentId: string; readonly epoch: string; readonly seq: number
  readonly at: number; readonly performanceNow: number; readonly timeOrigin: number
  readonly kind: string; readonly detail: Readonly<Record<string, Value>>
}
export interface TaskScope {
  readonly instanceToken: string; readonly executionId: string; readonly incidentId: string
  readonly generation: number; readonly readSequence: number
}
export interface ReadTicket extends TaskScope { readonly token: string; readonly epoch: string; readonly externalSignalToken: string }
/** 写owner在CSRF await之前冻结；预留标记最多消费一次。 */
export interface WriteTicket extends TaskScope { readonly token: string; readonly epoch: string; readonly reservedToken: string }
export interface HttpTicket {
  readonly token: string; readonly ownerToken: string; readonly role: string
  readonly internalSignalToken: string; readonly marker: string | null
}
interface Spec { version: 1; documentId: string; executionId: string; incidentId: string; executionPath: string; deadlineAt: number }
/** 测试可注入确定性时钟；真实模块只使用本机文档和原生随机数。 */
export interface ObservationEnvironment {
  readonly dev: boolean; readonly origin: string; readonly path: string
  readonly now: () => number; readonly date: () => number; readonly random: () => string; readonly timeOrigin: number
}
interface ReadRecord { ticket: ReadTicket; signal: AbortSignal; planId: string | null; retired: boolean; tailed: boolean; roles: Set<string>; release: () => void }
interface WriteRecord { ticket: WriteTicket; used: boolean; tailed: boolean }
interface HttpRecord { ticket: HttpTicket; done: boolean; signal: AbortSignal; release: () => void; firstCause: string | null }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const TOKEN = /^[0-9a-f]{32}$/
const HEADER = 'X-Ordivane-Diagnostic-Request'
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function specFrom(value: unknown): Spec | null {
  if (!record(value) || value.version !== 1 || typeof value.documentId !== 'string' || !TOKEN.test(value.documentId)
    || typeof value.executionId !== 'string' || !UUID.test(value.executionId) || typeof value.incidentId !== 'string' || !UUID.test(value.incidentId)
    || typeof value.executionPath !== 'string' || value.executionPath !== `/incidents/${value.incidentId}/executions/${value.executionId}` || typeof value.deadlineAt !== 'number' || !Number.isFinite(value.deadlineAt)
    || Object.keys(value).sort().join(',') !== 'deadlineAt,documentId,executionId,executionPath,incidentId,version') return null
  return { version: 1, documentId: value.documentId, executionId: value.executionId, incidentId: value.incidentId,
    executionPath: value.executionPath, deadlineAt: value.deadlineAt }
}

/** 单文档/epoch有界状态；所有错误只使诊断FAILED，不改变调用方的业务分支。 */
export class ReadTaskObserver {
  private status: ObservationState = 'ACTIVE'
  private sequence = 0
  private elapsed = 0
  private firstClose: number | null = null
  private deadline: number
  private readonly epoch: string
  private readonly reads = new Map<string, ReadRecord>()
  private readonly writes = new Map<string, WriteRecord>()
  private readonly httpCalls = new Map<string, HttpRecord>()
  private readonly timers = new Map<string, { owner: string; fired: boolean; cancelled: boolean }>()
  private bySignal = new WeakMap<AbortSignal, ReadRecord>()
  private signalTokens = new WeakMap<AbortSignal, string>()
  private readonly releases = new Set<() => void>()
  private readonly issued = new Set<string>()
  failureCode: string | null = null
  constructor(private readonly spec: Spec, private readonly sink: (event: ObservationEvent) => void, private readonly env: ObservationEnvironment) {
    this.epoch = env.random()
    this.deadline = env.now() + Math.max(0, spec.deadlineAt - env.date())
    this.emit('observer-ready', { mode: 'TAIL_ONLY', promiseSettlementObserved: false, settlementAt: null })
  }
  get state(): ObservationState { return this.status }
  /** 阶段切换只在ACTIVE时更新；首次close后的continuation绝不续期。 */
  setDeadlineAt(at: number): void {
    if (this.status !== 'ACTIVE' || !Number.isFinite(at)) { this.fail('DEADLINE_UPDATE_REJECTED'); return }
    this.deadline = this.env.now() + Math.max(0, at - this.env.date())
    this.emit('stage-deadline', { deadlineAt: at })
  }
  private token(): string {
    let value: string
    try { value = this.env.random() } catch { this.fail('RANDOM_FAILED'); return '' }
    if (!TOKEN.test(value) || this.issued.has(value)) { this.fail('TOKEN_COLLISION'); return '' }
    this.issued.add(value); return value
  }
  private signalToken(signal: AbortSignal): string {
    const old = this.signalTokens.get(signal)
    if (old) return old
    const token = this.token(); this.signalTokens.set(signal, token); return token
  }
  private listen(signal: AbortSignal, callback: () => void): () => void {
    if (signal.aborted) { callback(); return () => {} }
    const handler = () => { remove(); callback() }
    const remove = () => { signal.removeEventListener('abort', handler); this.releases.delete(remove) }
    signal.addEventListener('abort', handler, { once: true }); this.releases.add(remove); return remove
  }
  private canAdmit(): boolean {
    if (this.status !== 'ACTIVE') return false
    if (this.env.now() >= this.deadline) { this.fail('STAGE_EXPIRED'); return false }
    return true
  }
  private scopeValid(scope: TaskScope): boolean {
    return TOKEN.test(scope.instanceToken) && scope.executionId === this.spec.executionId && scope.incidentId === this.spec.incidentId
      && Number.isSafeInteger(scope.generation) && scope.generation >= 0 && Number.isSafeInteger(scope.readSequence) && scope.readSequence >= 0
  }
  private fields(scope: TaskScope): Record<string, Value> {
    return { instanceToken: scope.instanceToken, executionId: scope.executionId, incidentId: scope.incidentId,
      capturedGeneration: scope.generation, capturedReadSequence: scope.readSequence }
  }
  private emit(kind: string, detail: Readonly<Record<string, Value>>): void {
    if (this.status === 'FAILED' || this.status === 'SEALED') return
    if (this.firstClose !== null && this.env.now() >= Math.min(this.firstClose + 10000, this.deadline)) { this.fail('CLOSE_EXPIRED'); return }
    const begin = this.env.now()
    try {
      if (++this.sequence > 2048) { this.fail('EVENT_LIMIT'); return }
      const event: ObservationEvent = { version: 3, documentId: this.spec.documentId, epoch: this.epoch, seq: this.sequence,
        at: this.env.date(), performanceNow: begin, timeOrigin: this.env.timeOrigin, kind, detail }
      if (new TextEncoder().encode(JSON.stringify(event)).byteLength > 8192) { this.fail('PACKET_LIMIT'); return }
      this.sink(event)
      const cost = this.env.now() - begin; this.elapsed += cost
      if (cost > 2 || this.elapsed > 100) this.fail('OBSERVATION_TIME_LIMIT')
    } catch { this.fail('SINK_FAILED') }
  }
  fail(code: string): void {
    if (this.status === 'SEALED' || this.status === 'FAILED') return
    this.failureCode = code; this.status = 'FAILED'; this.release()
    this.reads.clear(); this.writes.clear(); this.httpCalls.clear(); this.timers.clear(); this.issued.clear()
  }
  private release(): void { for (const remove of this.releases) { try { remove() } catch { this.failureCode ??= 'LISTENER_RELEASE_FAILED' } }; this.releases.clear(); this.bySignal = new WeakMap(); this.signalTokens = new WeakMap() }
  note(cause: 'VISIBILITY' | 'ONLINE' | 'OFFLINE'): void { this.emit('environment', { cause }) }
  instance(): string | null { return this.canAdmit() ? this.token() : null }
  admitRead(scope: TaskScope, signal: AbortSignal, cause: ObservationCause, sourceToken: string | null = null): ReadTicket | null {
    if (!this.canAdmit()) return null
    if (!this.scopeValid(scope) || this.bySignal.has(signal)) { this.fail('READ_SCOPE'); return null }
    if (cause === 'TIMER' && (!sourceToken || !this.timers.get(sourceToken)?.fired || this.timers.get(sourceToken)?.cancelled)) { this.fail('TIMER_SOURCE'); return null }
    if (cause === 'WRITE_RECHECK' && (!sourceToken || !this.writes.has(sourceToken))) { this.fail('WRITE_RECHECK_SOURCE'); return null }
    if (this.reads.size + this.writes.size >= 64) { this.fail('TASK_LIMIT'); return null }
    if ([...this.reads.values()].some(r => !r.retired && !r.tailed && r.ticket.executionId === scope.executionId)) { this.fail('ACTIVE_TASK_OVERLAP'); return null }
    const token = this.token(), externalSignalToken = this.signalToken(signal)
    if (this.status !== 'ACTIVE') return null
    const ticket: ReadTicket = Object.freeze({ ...scope, token, epoch: this.epoch, externalSignalToken })
    const r: ReadRecord = { ticket, signal, planId: null, retired: false, tailed: false, roles: new Set(), release: () => {} }
    this.reads.set(token, r); this.bySignal.set(signal, r)
    this.emit('task-admitted', { ...this.fields(scope), taskToken: token, externalSignalToken, cause, sourceToken })
    r.release = this.listen(signal, () => this.emit('signal-abort', { taskToken: token, externalSignalToken, aborted: signal.aborted }))
    return ticket
  }
  bindPlan(ticket: ReadTicket | null, planId: string): void {
    if (!ticket) return
    const r = this.reads.get(ticket.token)
    if (r?.ticket !== ticket || !UUID.test(planId)) { this.fail('PLAN_BINDING'); return }
    r.planId = planId; this.emit('task-plan', { taskToken: ticket.token, planId })
  }
  invalidate(signal: AbortSignal, generation: number, readSequence: number, cause: RetireReason): void {
    const r = this.bySignal.get(signal); if (!r) return
    this.emit('task-invalidated', { taskToken: r.ticket.token, currentGeneration: generation, currentReadSequence: readSequence,
      barrierRevoked: generation !== r.ticket.generation || readSequence !== r.ticket.readSequence, cause })
  }
  retire(signal: AbortSignal, generation: number, readSequence: number): void {
    const r = this.bySignal.get(signal); if (!r) return
    if (!signal.aborted || (generation === r.ticket.generation && readSequence === r.ticket.readSequence)) { this.fail('RETIREMENT_UNPROVEN'); return }
    r.retired = true; this.emit('task-retired', { taskToken: r.ticket.token, externalSignalToken: r.ticket.externalSignalToken,
      currentGeneration: generation, currentReadSequence: readSequence, aborted: true, barrierRevoked: true })
  }
  guard(ticket: ReadTicket | null, stage: GuardStage, applies: boolean, generation: number, readSequence: number): void {
    if (!ticket) return
    if (this.reads.get(ticket.token)?.ticket !== ticket) { this.fail('UNKNOWN_TASK'); return }
    this.emit('guard', { taskToken: ticket.token, stage, decision: applies ? 'APPLY' : 'SKIP', currentGeneration: generation, currentReadSequence: readSequence })
  }
  effect(ticket: ReadTicket | null, effect: Effect, delayMs: number | null = null): void {
    if (ticket) this.emit('effect', { taskToken: ticket.token, effect, delayMs })
  }
  /** 诊断token只绑定原定时器；不创建、取消或等待任何业务timer。 */
  scheduleTimer(ticket: ReadTicket | null, delayMs: number): string | null {
    if (!ticket || this.status !== 'ACTIVE') return null
    if (this.reads.get(ticket.token)?.ticket !== ticket || this.timers.size >= 256 || !Number.isFinite(delayMs) || delayMs < 0) { this.fail('TIMER_SCOPE'); return null }
    const token = this.token(); if (this.failureCode !== null) return null
    this.timers.set(token, { owner: ticket.token, fired: false, cancelled: false })
    this.effect(ticket, 'TIMER', delayMs); this.emit('timer-scheduled', { timerToken: token, ownerToken: ticket.token, delayMs }); return token
  }
  fireTimer(token: string | null): void {
    if (!token) return
    const timer = this.timers.get(token)
    if (!timer || timer.fired || timer.cancelled || this.reads.get(timer.owner)?.retired) { this.fail('TIMER_FIRE'); return }
    timer.fired = true; this.emit('timer-fired', { timerToken: token, ownerToken: timer.owner })
  }
  cancelTimer(token: string | null): void {
    if (!token) return
    const timer = this.timers.get(token)
    if (timer && !timer.fired && !timer.cancelled) { timer.cancelled = true; this.emit('timer-cancelled', { timerToken: token, ownerToken: timer.owner }) }
  }
  tail(ticket: ReadTicket | null): void {
    if (!ticket) return
    const r = this.reads.get(ticket.token)
    if (r?.ticket !== ticket || r.tailed) { this.fail('TAIL_OWNER'); return }
    r.tailed = true; r.release()
    this.emit('task-tail', { taskToken: ticket.token, promiseSettlementObserved: false, settlementAt: null,
      pendingChildTokens: [...this.httpCalls.values()].filter(h => h.ticket.ownerToken === ticket.token && !h.done).map(h => h.ticket.token) })
  }
  admitWrite(scope: TaskScope): WriteTicket | null {
    if (!this.canAdmit()) return null
    if (!this.scopeValid(scope) || this.reads.size + this.writes.size >= 64 || [...this.writes.values()].some(w => !w.tailed)) { this.fail('WRITE_SCOPE'); return null }
    const token = this.token(), reservedToken = this.token()
    if (this.status !== 'ACTIVE') return null
    const ticket: WriteTicket = Object.freeze({ ...scope, token, epoch: this.epoch, reservedToken })
    this.writes.set(token, { ticket, used: false, tailed: false })
    this.emit('write-admitted', { ...this.fields(scope), writeToken: token, reservedToken, externalSignalToken: null })
    return ticket
  }
  writeGuard(ticket: WriteTicket | null, stage: 'RESULT' | 'ERROR' | 'FINALLY', applies: boolean, generation: number): void {
    if (ticket) this.emit('write-guard', { writeToken: ticket.token, stage, decision: applies ? 'APPLY' : 'SKIP', currentGeneration: generation })
  }
  writeEffect(ticket: WriteTicket | null, effect: Effect): void { if (ticket) this.emit('write-effect', { writeToken: ticket.token, effect }) }
  writeTail(ticket: WriteTicket | null): void {
    if (!ticket) return
    const r = this.writes.get(ticket.token); if (r?.ticket !== ticket) { this.fail('WRITE_OWNER'); return }
    r.tailed = true; this.emit('write-tail', { writeToken: ticket.token, promiseSettlementObserved: false, settlementAt: null })
  }
  closeAdmission(reason: CloseReason): void {
    if (this.status !== 'ACTIVE') return
    this.firstClose = this.env.now(); this.status = 'RECORD_ONLY'
    this.emit('scope-close', { reason, closeAt: this.firstClose, continuationUntil: Math.min(this.firstClose + 10000, this.deadline) })
  }
  seal(reason: 'PAGEHIDE' | 'CONTEXT_DESTROYED' | 'CONTROLLER'): void {
    if (this.status === 'SEALED' || this.status === 'FAILED') { this.release(); return }
    this.emit('observer-sealed', { reason }); this.status = 'SEALED'; this.release()
    this.reads.clear(); this.writes.clear(); this.httpCalls.clear(); this.timers.clear(); this.issued.clear()
  }
  http(path: string, init: RequestInit, internalSignal: AbortSignal, write?: WriteTicket | null): HttpTicket | null {
    if (this.status === 'SEALED' || this.status === 'FAILED') return null
    const method = init.method ?? 'GET'
    let ownerToken: string, role: string, marker: string | null, externalSignalToken: string | null = null
    if (method === 'POST' && path === `/api/v1/executions/${this.spec.executionId}/cancellations` && write) {
      const w = this.writes.get(write.token)
      if (w?.ticket !== write || write.epoch !== this.epoch || w.used || w.tailed) { this.fail('WRITE_OWNER_OR_REUSE'); return null }
      if (this.env.now() >= this.deadline || (this.firstClose !== null && this.env.now() >= this.firstClose + 10000)) { this.fail('CONTINUATION_EXPIRED'); return null }
      w.used = true; ownerToken = write.token; role = 'CANCEL_POST'; marker = write.reservedToken
    } else if (method === 'GET' && init.signal) {
      const r = this.bySignal.get(init.signal)
      if (!r) return null
      if (!this.canAdmit()) { this.fail('READ_AFTER_CLOSE'); return null }
      role = path === `/api/v1/executions/${r.ticket.executionId}` ? 'EXECUTION_GET'
        : r.planId && path === `/api/v1/recovery-plans/${r.planId}` ? 'LINKED_PLAN_GET'
          : path === `/api/v1/incidents/${r.ticket.incidentId}` ? 'LINKED_INCIDENT_GET' : ''
      if (!role || r.roles.has(role)) { this.fail('HTTP_ROLE'); return null }
      r.roles.add(role); ownerToken = r.ticket.token; externalSignalToken = r.ticket.externalSignalToken
      marker = init.signal.aborted ? null : this.token()
    } else return null
    if (this.httpCalls.size >= 256) { this.fail('HTTP_LIMIT'); return null }
    const token = marker ?? this.token(), internalSignalToken = this.signalToken(internalSignal)
    if (this.failureCode !== null) return null
    const ticket: HttpTicket = Object.freeze({ token, ownerToken, role, internalSignalToken, marker })
    const r: HttpRecord = { ticket, done: false, signal: internalSignal, release: () => {}, firstCause: null }
    this.httpCalls.set(token, r)
    this.emit('http-registered', { childToken: token, ownerToken, role, method, path, externalSignalToken, internalSignalToken,
      preAborted: init.signal?.aborted ?? false, marker: marker !== null })
    r.release = this.listen(internalSignal, () => this.emit('http-abort', { childToken: token, internalSignalToken,
      externalAborted: init.signal?.aborted ?? false, aborted: internalSignal.aborted, firstCause: r.firstCause }))
    return ticket
  }
  headers(original: HeadersInit | undefined, ticket: HttpTicket): HeadersInit | undefined {
    if (this.status === 'FAILED' || this.status === 'SEALED' || !ticket.marker) return original
    try {
      const copy = new Headers(original)
      if (copy.has(HEADER)) { this.fail('HEADER_CONFLICT'); return original }
      copy.set(HEADER, ticket.marker); return copy
    } catch { this.fail('HEADER_MERGE_FAILED'); return original }
  }
  httpPhase(ticket: HttpTicket | null, phase: 'BEFORE_FETCH' | 'HEADERS' | 'BODY' | 'FINALLY' | 'AUTH' | 'TIMEOUT' | 'EXTERNAL_CANCEL', status: number | null = null): void {
    if (!ticket) return
    const r = this.httpCalls.get(ticket.token)
    if (r?.ticket !== ticket) { this.fail('HTTP_OWNER'); return }
    if ((phase === 'TIMEOUT' || phase === 'EXTERNAL_CANCEL') && !r.signal.aborted && r.firstCause === null) r.firstCause = phase
    if (phase === 'FINALLY') { r.done = true; r.release() }
    this.emit('http-phase', { childToken: ticket.token, phase, status })
  }
}

/** 纯构造入口供实际模块测试；不安装到全局业务会话。 */
export function createReadObserver(value: unknown, sink: (event: ObservationEvent) => void, env: ObservationEnvironment): ReadTaskObserver | null {
  const spec = specFrom(value)
  if (!spec || !env.dev || env.origin !== 'http://127.0.0.1:5175' || env.path !== spec.executionPath) return null
  return new ReadTaskObserver(spec, sink, env)
}
let installed: ReadTaskObserver | null = null
/** 仅准确新文档启动时消费一次工厂；不读取.env、存储、URL参数或远端配置。 */
function bootstrap(): void {
  if (!import.meta.env.DEV || typeof window === 'undefined' || typeof document === 'undefined' || window.top !== window) return
  const key = Symbol.for('ordivane.readTaskObservation.bootstrap.v1')
  try {
    const factory: unknown = Reflect.get(globalThis, key)
    if (typeof factory !== 'function') return
    if (!Reflect.deleteProperty(globalThis, key)) return
    const value: unknown = factory()
    if (!record(value) || typeof value.sink !== 'function') return
    const sink = value.sink
    installed = createReadObserver(value.spec, event => { sink(event) }, {
      dev: true, origin: location.origin, path: location.pathname, now: () => performance.now(), date: () => Date.now(), timeOrigin: performance.timeOrigin,
      random: () => [...crypto.getRandomValues(new Uint8Array(16))].map(v => v.toString(16).padStart(2, '0')).join(''),
    })
    if (installed) window.addEventListener('pagehide', () => installed?.seal('PAGEHIDE'), { once: true })
  } catch { installed?.fail('BOOTSTRAP_FAILED') }
}
bootstrap()
/** OFF没有会话；失败/关闭实例仍可被调用方持有，但所有接口均失败闭合。 */
export function getReadObserver(): ReadTaskObserver | null { return installed }
