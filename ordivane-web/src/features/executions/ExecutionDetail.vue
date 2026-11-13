<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import RequestError from '../../components/RequestError.vue'
import { HttpFailure } from '../../lib/http/client'
import { useSession } from '../auth/session'
import { dateTime, getIncident } from '../incidents/api'
import type { IncidentDetail } from '../incidents/api'
import JsonDocument from '../incidents/JsonDocument.vue'
import { actions, getPlan } from '../recovery-plans/api'
import type { Plan } from '../recovery-plans/api'
import { cancelExecution, getExecution, statusNames } from './api'
import type { Execution } from './api'
import { pollingDelay } from './polling'
import { getReadObserver } from '../../lib/diagnostics/read-task-observer'
import type { ObservationCause, ReadTicket, RetireReason } from '../../lib/diagnostics/read-task-observer'

const route = useRoute(), session = useSession()
const id = computed(() => String(route.params.executionId ?? '')), incidentId = computed(() => String(route.params.incidentId ?? ''))
const execution = ref<Execution | null>(null), plan = ref<Plan | null>(null), incident = ref<IncidentDetail | null>(null)
const error = ref<unknown>(null), writeError = ref<unknown>(null), notice = ref(''), stopped = ref(''), updated = ref('')
const loading = ref(false), busy = ref(false), heading = ref<HTMLElement | null>(null)
const dialog = ref<HTMLDialogElement | null>(null), dialogHeading = ref<HTMLElement | null>(null)
const height = ref(window.visualViewport?.height ?? window.innerHeight)
const offset = ref(window.visualViewport?.offsetTop ?? 0)
let generation = 0, readSequence = 0, failures = 0, timer: number | undefined, controller: AbortController | null = null
const observer = getReadObserver(), observationInstance = observer?.instance() ?? null
let observationTimer: string | null = null
let background: { restore: () => void; x: number; y: number; trigger: HTMLElement | null; generation: number } | null = null
const timeline = computed(() => [...(execution.value?.timeline ?? [])].sort((a,b) => Date.parse(a.recordedAt)-Date.parse(b.recordedAt)))
const canCancel = computed(() => session.identity?.role === 'OPERATOR' && execution.value !== null && !error.value && !loading.value && !busy.value
  && execution.value.status !== 'CANCELLED' && execution.value.status !== 'SUCCEEDED'
  && (!execution.value.actionCompleted || execution.value.nextCheckAt !== null) && execution.value.cancelRequestedAt === null)
/** 页面可见在线才发起只读查询；失败上限在客户端独立保持。 */
function active() { return document.visibilityState === 'visible' && navigator.onLine }
function clearRead(cause: RetireReason = 'ROUTE') {
  const old = controller
  observer?.cancelTimer(observationTimer); observationTimer = null
  window.clearTimeout(timer); timer = undefined; readSequence++
  if (old) observer?.invalidate(old.signal, generation, readSequence, cause)
  controller?.abort(); controller = null; loading.value = false
  if (old) observer?.retire(old.signal, generation, readSequence)
}
/** 按服务端建议顺序调度；极远期限分段等待，不产生高频GET。 */
function schedule(observation: ReadTicket | null = null) {
  observer?.cancelTimer(observationTimer); observationTimer = null
  window.clearTimeout(timer)
  observer?.guard(observation, 'SCHEDULE', active() && !stopped.value && execution.value !== null, generation, readSequence)
  if (!active() || stopped.value || !execution.value) return
  const delay = pollingDelay(execution.value)
  if (delay === null) return
  const timerToken = observer?.scheduleTimer(observation, Math.min(delay, 2147483647)) ?? null
  observationTimer = timerToken
  timer = window.setTimeout(() => { observer?.fireTimer(timerToken); void load(false, false, 'TIMER', timerToken) }, Math.min(delay, 2147483647))
}
/** 三资源准确关联后才展示；所有完成、错误和finally均有代际屏障。 */
async function load(manual = false, focus = false, cause: ObservationCause = 'MANUAL', sourceToken: string | null = null) {
  if (!active() || loading.value) return
  if (manual) { failures = 0; stopped.value = '' }
  if (stopped.value) return
  window.clearTimeout(timer)
  const g = generation, seq = ++readSequence, eid = id.value, iid = incidentId.value
  const abort = new AbortController(); controller = abort
  loading.value = true
  const observation = observationInstance ? observer?.admitRead({ instanceToken: observationInstance, executionId: eid, incidentId: iid, generation: g, readSequence: seq }, abort.signal, cause, sourceToken) ?? null : null
  try {
    const result = await getExecution(eid, abort.signal)
    observer?.bindPlan(observation, result.planId)
    const [linkedPlan, linkedIncident] = await Promise.all([getPlan(iid,result.planId,abort.signal),getIncident(iid,abort.signal)])
    observer?.guard(observation, 'RESULT', g === generation && seq === readSequence, generation, readSequence)
    if (g !== generation || seq !== readSequence) return
    if (linkedPlan.version !== result.version || linkedPlan.digest !== result.digest || linkedIncident.event_id !== result.eventId) throw new HttpFailure(0,'ASSOCIATION_MISMATCH','执行与案件或计划关联不匹配')
    execution.value = result; plan.value = linkedPlan; incident.value = linkedIncident; error.value = null; failures = 0; updated.value = new Date().toISOString()
    observer?.effect(observation, 'DATA')
    if (focus) { await nextTick(); observer?.guard(observation, 'FOCUS', g === generation && seq === readSequence, generation, readSequence); if (g === generation && seq === readSequence) { heading.value?.focus(); observer?.effect(observation, 'FOCUS') } }
  } catch (failure) {
    observer?.guard(observation, 'ERROR', g === generation && seq === readSequence && !abort.signal.aborted, generation, readSequence)
    if (g !== generation || seq !== readSequence || abort.signal.aborted) return
    error.value = failure; failures++
    observer?.effect(observation, 'ERROR')
    if (failure instanceof HttpFailure && [401,403,404].includes(failure.status)) {
      execution.value = null; plan.value = null; incident.value = null; stopped.value = '访问失败，已停止自动查询'
      observer?.effect(observation, 'STOP')
    } else if (failures >= 3) { stopped.value = '连续查询失败 3 次，已停止自动查询'; observer?.effect(observation, 'STOP') }
  } finally {
    observer?.guard(observation, 'FINALLY', g === generation && seq === readSequence, generation, readSequence)
    if (g === generation && seq === readSequence) {
      loading.value = false; controller = null
      observer?.effect(observation, 'LOADING')
      if (error.value && !stopped.value && active()) { const timerToken = observer?.scheduleTimer(observation, 5000) ?? null; observationTimer = timerToken; timer = window.setTimeout(() => { observer?.fireTimer(timerToken); void load(false, false, 'TIMER', timerToken) },5000) }
      else schedule(observation)
    }
    observer?.tail(observation)
  }
}
/** 关闭仅解除界面锁，不代表撤销服务端执行。 */
function closeConfirmation(leave = false) {
  const owned = background; background = null; dialog.value?.close(); owned?.restore()
  if (!leave && owned?.generation === generation) {
    window.scrollTo({ left: owned.x, top: owned.y, behavior:'instant' })
    if (owned.trigger?.isConnected && !owned.trigger.matches(':disabled')) owned.trigger.focus({preventScroll:true})
    else heading.value?.focus({preventScroll:true})
  }
}
/** 默认聚焦标题；沿用既有背景样式优先级和内部滚动规则。 */
function openConfirmation() {
  if (!canCancel.value || dialog.value?.open) return
  const root = document.documentElement
  const styles = ['overflow','scrollbar-gutter'].map(key => ({key,value:root.style.getPropertyValue(key),priority:root.style.getPropertyPriority(key)}))
  background = { generation, x:window.scrollX,y:window.scrollY,trigger:document.activeElement instanceof HTMLElement ? document.activeElement : null,
    restore() { for (const s of styles) { if (root.style.getPropertyValue(s.key) !== (s.key === 'overflow' ? 'hidden' : 'stable') || root.style.getPropertyPriority(s.key)) continue; if(s.value) root.style.setProperty(s.key,s.value,s.priority); else root.style.removeProperty(s.key) } } }
  root.style.setProperty('overflow','hidden'); root.style.setProperty('scrollbar-gutter','stable')
  dialog.value?.showModal(); dialogHeading.value?.focus()
}
/** Tab不离开模态；Escape及原生关闭统一释放锁。 */
function trap(event: KeyboardEvent) {
  if(event.key !== 'Tab') return
  const controls = [...(dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])], first = controls[0], last = controls.at(-1)
  if(event.shiftKey && (document.activeElement === first || document.activeElement === dialogHeading.value)) { event.preventDefault(); last?.focus() }
  else if(!event.shiftKey && (document.activeElement === last || document.activeElement === dialogHeading.value)) { event.preventDefault(); first?.focus() }
}
/** 无正文取消；响应丢失仍GET查证，202绝不当作实际取消。 */
async function cancel() {
  if(!canCancel.value || !execution.value) return
  const g = generation, eid = execution.value.id
  const writeObservation = observationInstance ? observer?.admitWrite({ instanceToken: observationInstance, executionId: eid, incidentId: incidentId.value, generation: g, readSequence }) ?? null : null
  busy.value = true; writeError.value = null; notice.value = ''; clearRead('WRITE_PREP'); closeConfirmation()
  try {
    const result = await cancelExecution(eid, writeObservation)
    observer?.writeGuard(writeObservation, 'RESULT', g === generation, generation)
    if(g !== generation) return
    notice.value = result.status === 202 ? '取消请求已受理；尚未实际取消，继续查证业务结果。'
      : result.execution.status === 'CANCELLED' && result.execution.cancelledAt !== null ? '已实际取消，请查看取消时间和范围。' : '取消结果待核验。'
    observer?.writeEffect(writeObservation, 'NOTICE')
  } catch(failure) {
    observer?.writeGuard(writeObservation, 'ERROR', g === generation, generation)
    if(g === generation) { writeError.value = failure; notice.value = failure instanceof HttpFailure && failure.status === 409 ? `取消被拒绝（${failure.code}），正在回查实际状态。` : '取消结果未确认，正在回查；停止等待不代表服务端撤销。' }
    if(g === generation) observer?.writeEffect(writeObservation, 'ERROR')
  } finally { observer?.writeGuard(writeObservation, 'FINALLY', g === generation, generation); if(g === generation) { busy.value = false; observer?.writeEffect(writeObservation, 'BUSY'); await load(true, false, 'WRITE_RECHECK', writeObservation?.token ?? null) }; observer?.writeTail(writeObservation) }
}
/** 隐藏/离线取消在途GET；恢复先刷新，停止条件不被可见性重置。 */
function visibility() { observer?.note(navigator.onLine ? 'VISIBILITY' : 'OFFLINE'); if(!active()) clearRead(navigator.onLine ? 'HIDDEN' : 'OFFLINE'); else if(!stopped.value) void load(false, false, 'VISIBILITY') }
function resize() { height.value = window.visualViewport?.height ?? window.innerHeight; offset.value = window.visualViewport?.offsetTop ?? 0 }
document.addEventListener('visibilitychange',visibility); window.addEventListener('online',visibility); window.addEventListener('offline',visibility)
window.addEventListener('resize',resize); window.visualViewport?.addEventListener('resize',resize); window.visualViewport?.addEventListener('scroll',resize)
watch(() => [id.value,incidentId.value,session.identity?.id],() => {
  closeConfirmation(true); generation++; clearRead('IDENTITY'); execution.value = null; plan.value = null; incident.value = null
  error.value = null; writeError.value = null; notice.value = ''; stopped.value = ''; updated.value = ''; busy.value = false; failures = 0
  void load(false,true,'IDENTITY_OR_ROUTE')
},{immediate:true})
onBeforeRouteUpdate(() => { observer?.closeAdmission('ROUTE'); closeConfirmation(true); generation++; clearRead('ROUTE') })
onBeforeRouteLeave(() => { observer?.closeAdmission('ROUTE'); closeConfirmation(true); generation++; clearRead('ROUTE') })
onBeforeUnmount(() => {
  observer?.closeAdmission('UNMOUNT'); closeConfirmation(true); generation++; clearRead('UNMOUNT')
  document.removeEventListener('visibilitychange',visibility); window.removeEventListener('online',visibility); window.removeEventListener('offline',visibility)
  window.removeEventListener('resize',resize); window.visualViewport?.removeEventListener('resize',resize); window.visualViewport?.removeEventListener('scroll',resize)
})
</script>

<template>
  <section class="incidents-page execution-page">
    <nav class="incident-breadcrumb" aria-label="面包屑"><RouterLink :to="`/incidents/${incidentId}`">案件</RouterLink><template v-if="plan"> / <RouterLink :to="`/incidents/${incidentId}/plans/${plan.id}`">计划 v{{ plan.version }}</RouterLink></template> / 执行</nav>
    <div class="incident-page-header"><h1 ref="heading" tabindex="-1">执行详情</h1><button :disabled="loading || busy" @click="load(true)">{{ loading ? '刷新中…' : '刷新结果' }}</button></div>
    <RequestError :error="error" /><RequestError :error="writeError" />
    <p v-if="error && execution">显示上次数据，尚未核验最新结果</p><p v-if="stopped" role="status">{{ stopped }}；可手动刷新结果</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loading && !execution" role="status">正在读取执行与关联依据…</p>
    <template v-if="execution && plan && incident">
      <p class="execution-state">{{ statusNames[execution.status] }}</p>
      <section><h2>业务核验</h2><dl class="incident-metadata"><div><dt>业务结果</dt><dd>{{ execution.businessVerified ? '已通过业务核验' : '尚未通过业务核验' }}</dd></div><div><dt>后端原因</dt><dd>{{ execution.reason || '空字符串' }}</dd></div><div><dt>动作完成</dt><dd>{{ execution.actionCompleted ? '已完成' : '未完成' }}</dd></div><div><dt>案件状态</dt><dd>{{ incident.status === 'RESOLVED' ? '已解决' : '待调查' }}</dd></div><div><dt>执行投影案件状态</dt><dd>{{ execution.incidentStatus }}</dd></div><div><dt>资格持有</dt><dd>{{ execution.eligibilityHeld ? '持有' : '未持有' }}</dd></div><div><dt>下一次查证</dt><dd>{{ execution.nextCheckAt === null ? '无后续安排' : dateTime(execution.nextCheckAt) }}</dd></div><div><dt>核验截止时间</dt><dd>{{ execution.verificationDeadline === null ? '无值' : dateTime(execution.verificationDeadline) }}</dd></div><div><dt>核验领取 / 上限</dt><dd>{{ execution.verificationCount }} / {{ execution.verificationLimit }}</dd></div><div><dt>最后成功刷新</dt><dd>{{ dateTime(updated) }}</dd></div></dl></section>
      <section><h2>执行依据</h2><p>{{ actions.find(a => a.value === execution?.action)?.label }} · {{ execution.action }}</p><dl class="incident-metadata"><div v-for="field in ['id','eventId','businessKey','planId','requesterId','digest','fingerprint','transportFingerprint'] as const" :key="field"><dt>{{ {id:'执行 ID',eventId:'事件 ID',businessKey:'业务资格键',planId:'计划 ID',requesterId:'发起人 ID',digest:'计划摘要',fingerprint:'请求指纹',transportFingerprint:'传输指纹'}[field] }}</dt><dd class="resource-id">{{ execution[field] }}</dd></div><div><dt>配置摘要</dt><dd class="resource-id">{{ plan.configurationDigest }}</dd></div><div><dt>创建时间</dt><dd>{{ dateTime(execution.createdAt) }}</dd></div></dl></section>
      <section><h2>投递与取消</h2><dl class="incident-metadata"><div><dt>投递状态</dt><dd>{{ execution.deliveryState ?? '不适用（无消息投递）' }}</dd></div><div><dt>取消请求时间</dt><dd>{{ execution.cancelRequestedAt === null ? '未请求' : dateTime(execution.cancelRequestedAt) }}</dd></div><div><dt>实际取消时间</dt><dd>{{ execution.cancelledAt === null ? '尚未实际取消' : dateTime(execution.cancelledAt) }}</dd></div><div><dt>资格释放时间</dt><dd>{{ execution.releasedAt === null ? '无值' : dateTime(execution.releasedAt) }}</dd></div></dl><button v-if="session.identity?.role === 'OPERATOR'" :disabled="!canCancel" @click="openConfirmation">请求取消</button></section>
      <section><h2>业务观测</h2><p v-if="!execution.observations.length">尚无业务观测</p><article v-for="item in execution.observations" :key="item.id"><h3>{{ item.verified ? '核验通过' : '核验未通过' }}</h3><p>{{ item.reason }}</p><p><time :title="item.observedAt">{{ dateTime(item.observedAt) }}</time></p><p class="resource-id">观测 ID：{{ item.id }}</p><p class="resource-id">SHA-256：{{ item.contentSha256 }}</p><details><summary>查询范围、时点与原始业务结果</summary><JsonDocument :text="item.rawContent ?? JSON.stringify(item.content,null,2)" label="业务观测" /></details></article></section>
      <section><h2>发送尝试</h2><p v-if="!execution.attempts.length">无发送尝试</p><article v-for="a in execution.attempts" :key="a.attemptId"><h3>{{ a.outcome }}{{ a.outcome === 'ACK' ? '（消息确认，非业务成功）' : '' }}</h3><p class="resource-id">{{ a.attemptId }}</p><p>{{ dateTime(a.startedAt) }} → {{ a.finishedAt === null ? '尚未结束' : dateTime(a.finishedAt) }}</p><p v-if="a.finishedAt !== null">耗时 {{ Date.parse(a.finishedAt)-Date.parse(a.startedAt) }} ms</p><p>围栏令牌 {{ a.fencingToken }}</p></article></section>
      <section><h2>核验领取记录</h2><p v-if="!execution.verificationAttempts.length">无核验领取</p><article v-for="a in execution.verificationAttempts" :key="a.attemptId"><p class="resource-id">核验尝试 ID：{{ a.attemptId }}</p><p>{{ dateTime(a.claimedAt) }} → 租约 {{ dateTime(a.leaseUntil) }}</p><p class="resource-id">观测 ID：{{ a.observationId ?? '尚无观测' }}</p><p>围栏令牌 {{ a.fencingToken }}</p></article></section>
      <section><h2>执行时间线</h2><p v-if="!timeline.length">暂无时间线</p><ol><li v-for="(item,index) in timeline" :key="index"><h3>{{ item.kind }}</h3><time :title="item.recordedAt">{{ dateTime(item.recordedAt) }}</time><JsonDocument :text="JSON.stringify(item.detail,null,2)" label="时间线明细" /></li></ol></section>
    </template>
    <dialog ref="dialog" class="execution-dialog" :class="{'low':height <= 480}" :style="{'--height':`${height}px`,'--top':`${offset}px`}" aria-labelledby="cancel-title" @cancel.prevent="closeConfirmation()" @close="!dialog?.open && closeConfirmation()" @keydown="trap">
      <header><h2 id="cancel-title" ref="dialogHeading" tabindex="-1">请求取消执行</h2><button @click="closeConfirmation()">关闭</button></header><div class="dialog-content"><p>仅阻止尚未发送的动作或后续只读查证。已开始或结果不确定时只能受理请求，不能保证撤回外部副作用。</p><p class="resource-id">执行 ID：{{ execution?.id }}</p><p>当前状态：{{ execution?.status }}</p></div><footer><button @click="closeConfirmation()">返回</button><button :disabled="!canCancel" @click="cancel">确认请求取消</button></footer>
    </dialog>
  </section>
</template>

<style scoped>
.execution-page { overflow-wrap:anywhere; }
h1,.execution-state { font-size:15px; line-height:24px; font-weight:600; }
section section { border-bottom:1px solid var(--ov-border); margin-block:24px; padding-bottom:24px; }
article,li { padding-block:16px; border-bottom:1px solid var(--ov-border); }
article p { margin-block:8px; }
ol { padding-left:20px; }
.execution-dialog { color:var(--ov-text); background:var(--ov-surface); border:1px solid var(--ov-border); border-radius:var(--ov-radius-md); width:min(520px,calc(100vw - 32px)); max-width:none; max-height:calc(var(--height) - 32px); padding:0; margin:auto; top:var(--top); }
.execution-dialog[open] { display:flex; flex-direction:column; }
.execution-dialog::backdrop { background:#0008; }
.execution-dialog header,.execution-dialog footer { padding:16px; display:flex; gap:8px; justify-content:space-between; }
.execution-dialog h2 { margin:0; }
.dialog-content { padding:0 16px; overflow-y:auto; min-height:0; }
.execution-dialog footer { border-top:1px solid var(--ov-border); }
.execution-dialog,.dialog-content { overscroll-behavior:contain; }
.execution-dialog.low[open] { display:block; overflow-y:auto; }
.low .dialog-content { overflow:visible; }
@media(width < 768px) { .execution-dialog footer { flex-direction:column; } }
</style>
