<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import RequestError from '../../components/RequestError.vue'
import { HttpFailure, isRecord } from '../../lib/http/client'
import { useSession } from '../auth/session'
import { dateTime, getEvidence, getIncident, jsonObject, scalar } from '../incidents/api'
import type { Evidence, IncidentDetail } from '../incidents/api'
import JsonDocument from '../incidents/JsonDocument.vue'
import { actions, createPlan, decide, exact, getPlan, precheck, submitApproval, uuidPattern } from './api'
import type { Action, Plan } from './api'
import { IntentStore } from '../executions/intent'
import { createExecution, getExecution } from '../executions/api'

const route = useRoute(), router = useRouter(), session = useSession()
const incidentId = computed(() => String(route.params.incidentId ?? ''))
const planId = computed(() => String(route.params.planId ?? ''))
const creating = computed(() => planId.value === 'new')
const previousId = computed(() => typeof route.query.previous === 'string' ? route.query.previous : '')
const incident = ref<IncidentDetail | null>(null), evidence = ref<Evidence[]>([]), plan = ref<Plan | null>(null)
const loading = ref(false), busy = ref(false), stale = ref(true)
const loadError = ref<unknown>(null), writeError = ref<unknown>(null), notice = ref(''), uncertainCreate = ref(false)
const action = ref<Action | ''>(''), delay = ref(''), selected = ref<string[]>([]), validation = ref('')
const heading = ref<HTMLElement | null>(null), feedback = ref<HTMLElement | null>(null)
const dialog = ref<HTMLDialogElement | null>(null), dialogHeading = ref<HTMLElement | null>(null)
const confirmation = ref<{ operation: 'SUBMIT' | 'APPROVE' | 'REJECT' | 'EXECUTE'; plan: Plan; precheckId: string; identityId: string } | null>(null)
const viewportHeight = ref(window.visualViewport?.height ?? window.innerHeight)
const viewportTop = ref(window.visualViewport?.offsetTop ?? 0)
const now = ref(Date.now())
let generation = 0, readVersion = 0
/** 一次确认弹层拥有的背景样式、位置和触发器，仅在原对象仍有效时恢复焦点。 */
interface ConfirmationPageState {
  generation: number; scrollX: number; scrollY: number; trigger: HTMLElement | null
  restoreStyles: () => void
}
let confirmationPage: ConfirmationPageState | null = null
let confirmationVersion = 0
const tick = window.setInterval(() => { now.value = Date.now() }, 1000)
const availableEvidence = computed(() => evidence.value.filter(item => item.outcome === 'COLLECTED'))
const payload = computed(() => jsonObject(incident.value?.original_event ?? null)?.payload)
const target = computed(() => isRecord(payload.value) ? payload.value : null)
const operator = computed(() => session.identity?.role === 'OPERATOR')
const fresh = computed(() => !loading.value && !busy.value && !stale.value && incident.value !== null)
const latestCheck = computed(() => plan.value?.prechecks[0])
const checkFresh = computed(() => !!latestCheck.value?.passed && Date.parse(latestCheck.value.expiresAt) > now.value)
const planTimedOut = computed(() => !!plan.value && Date.parse(plan.value.expiresAt) <= now.value)
const canPrecheck = computed(() => fresh.value && operator.value && plan.value?.status === 'DRAFT' && !planTimedOut.value)
const canSubmit = computed(() => canPrecheck.value && checkFresh.value && plan.value?.requesterId === session.identity?.id)
const canDecide = computed(() => fresh.value && session.identity?.role === 'APPROVER' && plan.value?.status === 'PENDING_APPROVAL'
  && plan.value.requesterId !== session.identity.id && checkFresh.value && !planTimedOut.value)
const states: Record<string, string> = { DRAFT: '草稿', PENDING_APPROVAL: '◷ 待审批', APPROVED: '◇ 已批准', REJECTED: '× 已拒绝',
  SUPERSEDED: '已有替代版本；当前版本只读', CONFIGURATION_CHANGED: '配置已变化；需要新版本重新预检和审批', EXPIRED: '计划已过期' }
const reasons: Record<string, string> = { EVENT_SCHEMA_INVALID: '原事件格式无效', SCENARIO_OR_ACTION_UNREGISTERED: '场景或动作未注册',
  TARGET_UNREGISTERED: '恢复目标未注册', ORDER_NOT_PAID_OR_REFUNDED: '订单未支付或已退款', ENTITLEMENT_EXISTS: '已有权益，禁止重放', DEPENDENCY_UNAVAILABLE: '事实查询依赖不可用' }
const executionMessage = ref('')
const firstExecution = ref(false), knownExecutionId = ref('')
const intentStore = new IntentStore({ getItem: key => window.localStorage.getItem(key), setItem: (key, value) => window.localStorage.setItem(key, value) })
const canStartExecution = computed(() => fresh.value && operator.value && plan.value?.status === 'APPROVED' && !planTimedOut.value)
const canExecute = computed(() => fresh.value && operator.value && plan.value !== null)
const operationLabel = computed(() => confirmation.value?.operation === 'EXECUTE' ? '确认执行 / 查证原意图' : confirmation.value?.operation === 'SUBMIT' ? '提交审批' : confirmation.value?.operation === 'APPROVE' ? '批准此版本' : '拒绝此版本')
const confirmationAllowed = computed(() => confirmation.value !== null && confirmation.value.plan.id === plan.value?.id
  && confirmation.value.plan.version === plan.value.version && confirmation.value.plan.digest === plan.value.digest && confirmation.value.identityId === session.identity?.id && (confirmation.value.operation === 'EXECUTE' ? canExecute.value : confirmation.value.operation === 'SUBMIT' ? canSubmit.value : canDecide.value))
/** 写请求的失败语义由操作场景补充，保留安全trace而不回显后端正文。 */
const writeMessage = computed(() => {
  if (!(writeError.value instanceof HttpFailure)) return ''
  const e = writeError.value
  if (uncertainCreate.value) return '创建结果未确认，尚未取得计划 ID。请保留追踪 ID 核验，勿盲目重试。'
  if (e.status === 409) return '版本、摘要、配置、期限或审批状态已变化。请核对刷新结果后重新审阅。'
  if (e.status === 403) return '当前身份或会话校验不允许此操作；不能审批本人提交的计划。请求未自动重发。'
  if (e.status === 400) return '请求未被接受，请检查动作参数、证据引用和前版信息。'
  if (e.status === 503) return '事实查询依赖暂不可用，请查看预检查历史。请求未自动重发。'
  return '操作结果未确认，已查询此计划核验；请先核对状态和记录，勿盲目重试。'
})
/** 同案刷新保留最后数据但阻止写；切对象的所有旧回调均失效。 */
async function load(focus = false) {
  const g = generation, r = ++readVersion, id = incidentId.value, pid = planId.value
  const prior = previousId.value, isNew = creating.value
  loading.value = true; stale.value = true; loadError.value = null
  try {
    if (isNew && prior && !uuidPattern.test(prior)) throw new HttpFailure(400, 'INVALID_REQUEST', '前版计划 ID 无效')
    const [details, snapshots, result] = await Promise.all([getIncident(id), getEvidence(id),
      isNew ? (prior ? getPlan(id, prior) : Promise.resolve(null)) : getPlan(id, pid)])
    if (g !== generation || r !== readVersion) return
    incident.value = details; evidence.value = snapshots; plan.value = result; stale.value = false
    if (focus) { await nextTick(); if (g === generation) heading.value?.focus() }
  } catch (error) {
    if (g !== generation || r !== readVersion) return
    loadError.value = error
    if (error instanceof HttpFailure && [401, 403, 404].includes(error.status)) {
      incident.value = null; evidence.value = []; plan.value = null
    }
  } finally { if (g === generation && r === readVersion) loading.value = false }
}
/** 仅选择当前案件已成功采集的证据，后端再次校验归属。 */
async function create() {
  if (!fresh.value || !operator.value || uncertainCreate.value) return
  validation.value = ''
  if (!action.value) validation.value = '请选择动作'
  else if (action.value === 'WAIT_AND_RECHECK' && (!/^\d+$/.test(delay.value) || Number(delay.value) < 1 || Number(delay.value) > 3600)) validation.value = '等待秒数必须为 1–3600 的整数'
  else if (selected.value.length < 1 || selected.value.length > 32 || selected.value.some(id => !availableEvidence.value.some(e => e.id === id))) validation.value = '请选择 1–32 份同案已采集证据'
  if (validation.value) { await nextTick(); feedback.value?.focus(); return }
  const chosen = action.value
  if (!chosen) return
  const g = generation, id = incidentId.value, prior = plan.value
  busy.value = true; writeError.value = null; notice.value = ''
  try {
    const created = await createPlan(id, { action: chosen, parameters: chosen === 'WAIT_AND_RECHECK' ? { delaySeconds: Number(delay.value) } : {},
      evidenceIds: [...selected.value], previous: prior ? { id: prior.id, ...exact(prior) } : null })
    if (g === generation) await router.push(`/incidents/${id}/plans/${created.id}`)
  } catch (error) {
    if (g === generation) {
      writeError.value = error
      uncertainCreate.value = !(error instanceof HttpFailure) || error.status === 0 || error.status >= 500
    }
  } finally { if (g === generation) { busy.value = false; await nextTick(); feedback.value?.focus() } }
}
/** 已知ID的POST只发一次；成功或失败后均GET核验，不以HTTP500推断回滚。 */
async function mutate(operation: 'PRECHECK' | 'SUBMIT' | 'APPROVE' | 'REJECT', snapshot: Plan, precheckId = '') {
  if (busy.value) return
  const g = generation
  busy.value = true; writeError.value = null; notice.value = ''
  const modalPage = closeConfirmation()
  try {
    if (operation === 'PRECHECK') await precheck(snapshot)
    else if (operation === 'SUBMIT') await submitApproval(snapshot, precheckId)
    else await decide(snapshot, operation)
    if (g === generation) notice.value = operation === 'PRECHECK' ? '预检查已记录，请查看结果' : operation === 'SUBMIT' ? '已提交此版本审批' : operation === 'APPROVE' ? '此版本已批准；批准不代表恢复成功' : '此版本已拒绝'
  } catch (error) { if (g === generation) writeError.value = error }
  finally {
    if (g === generation) {
      await load()
      if (g === generation) {
        busy.value = false; await nextTick()
        if (g === generation) {
          if (modalPage) {
            // 业务提示/记录重排后再恢复，避免浏览器滚动锚定移动原背景。
            window.scrollTo({ left: modalPage.scrollX, top: modalPage.scrollY, behavior: 'instant' })
            restoreConfirmationFocus(modalPage)
          }
          else feedback.value?.focus()
        }
      }
    }
  }
}
/** 背景锁只由本次弹层持有；保留原内联值/优先级，内部滚动不受影响。 */
function lockConfirmationBackground(trigger: HTMLElement | null): ConfirmationPageState {
  const root = document.documentElement
  const styles = ['overflow', 'scrollbar-gutter'].map(name => ({
    name, value: root.style.getPropertyValue(name), priority: root.style.getPropertyPriority(name),
  }))
  const state = {
    generation, scrollX: window.scrollX, scrollY: window.scrollY, trigger,
    restoreStyles() {
      for (const style of styles) {
        const ownedValue = style.name === 'overflow' ? 'hidden' : 'stable'
        if (root.style.getPropertyValue(style.name) !== ownedValue || root.style.getPropertyPriority(style.name)) continue
        if (style.value) root.style.setProperty(style.name, style.value, style.priority)
        else root.style.removeProperty(style.name)
      }
    },
  }
  root.style.setProperty('scrollbar-gutter', 'stable')
  root.style.setProperty('overflow', 'hidden')
  return state
}
/** 已卸载、隐藏或禁用的触发器不可回焦；收尾反馈不会滚动原背景位置。 */
function restoreConfirmationFocus(state: ConfirmationPageState) {
  if (state.generation !== generation) return
  const trigger = state.trigger
  if (!busy.value && trigger?.isConnected && !trigger.matches(':disabled') && !trigger.closest('[inert]') && trigger.getClientRects().length) {
    trigger.focus({ preventScroll: true })
  } else (feedback.value ?? heading.value)?.focus({ preventScroll: true })
}
/** 普通关闭恢复原页；离页先释放样式，不向新页面写旧滚动位置或延迟焦点。 */
function closeConfirmation(context: 'stay' | 'leave' = 'stay'): ConfirmationPageState | null {
  confirmationVersion++
  const state = confirmationPage
  confirmationPage = null
  dialog.value?.close(); confirmation.value = null
  state?.restoreStyles()
  if (context === 'stay' && state?.generation === generation) {
    window.scrollTo({ left: state.scrollX, top: state.scrollY, behavior: 'instant' })
    restoreConfirmationFocus(state)
  }
  return state
}
/** 原生close事件也释放资源；忽略旧close事件，不能关闭后来新开的弹层。 */
function onConfirmationClosed() {
  if (!dialog.value?.open && confirmationPage) closeConfirmation()
}
/** Tab在可用按钮之间循环，避免原生dialog末项将焦点交给浏览器工具栏。 */
function trapConfirmationFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')]
  const first = controls[0], last = controls.at(-1)
  if (!first || !last) { event.preventDefault(); dialogHeading.value?.focus(); return }
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === dialogHeading.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (active === last || active === dialogHeading.value)) { event.preventDefault(); first.focus() }
}
/** 确认绑定当前完整版本；默认聚焦标题而非决定按钮。 */
async function openConfirmation(operation: 'SUBMIT' | 'APPROVE' | 'REJECT' | 'EXECUTE') {
  if (!plan.value || dialog.value?.open || (operation === 'EXECUTE' ? !canExecute.value : operation === 'SUBMIT' ? !canSubmit.value : !canDecide.value)) return
  const version = ++confirmationVersion, currentGeneration = generation
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  firstExecution.value = false
  confirmation.value = { operation, plan: plan.value, precheckId: latestCheck.value?.id ?? '', identityId: session.identity?.id ?? '' }
  await nextTick()
  const currentDialog = dialog.value
  if (version !== confirmationVersion || currentGeneration !== generation || !confirmation.value || !currentDialog?.isConnected) return
  confirmationPage = lockConfirmationBackground(trigger)
  try { currentDialog.showModal(); dialogHeading.value?.focus() }
  catch (error) { closeConfirmation(); throw error }
}
/** 确认期间版本/时效变更需重新审阅，不能补发旧意图。 */
function confirm() {
  const c = confirmation.value
  if (c && confirmationAllowed.value) { if (c.operation === 'EXECUTE') void execute(c.plan, c.identityId); else void mutate(c.operation, c.plan, c.precheckId) }
}
/** 执行确认仅提交一次；未知结果原键查证，已知ID先GET，旧回调不得导航。 */
async function execute(snapshot: Plan, identityId: string) {
  if (busy.value || session.identity?.id !== identityId) return
  const g = generation
  busy.value = true; writeError.value = null; executionMessage.value = ''
  closeConfirmation()
  try {
    const binding = { identityId, planId: snapshot.id, ...exact(snapshot) }
    const prior = intentStore.read(binding)
    if (!prior && !firstExecution.value) throw new Error('执行结果未确认：未找到原幂等键，不能重新发送。请核查原执行。')
    if (!prior && (snapshot.status !== 'APPROVED' || Date.parse(snapshot.expiresAt) <= Date.now())) throw new Error('当前版本不能发起新执行；仅可查证原意图。')
    const intent = prior ?? intentStore.confirm(binding)
    const known = knownExecutionId.value || intent.executionId
    const result = known ? await getExecution(known) : await createExecution(intent)
    if (result.planId !== snapshot.id || result.version !== snapshot.version || result.digest !== snapshot.digest || result.requesterId !== identityId) throw new Error('执行关联不匹配，请保留原意图查证')
    if (g === generation && session.identity?.id === identityId) knownExecutionId.value = result.id
    intentStore.save({ ...intent, executionId: result.id })
    if (g === generation && session.identity?.id === identityId) await router.push(`/incidents/${snapshot.incidentId}/executions/${result.id}`)
  } catch (error) {
    if (g === generation && session.identity?.id === identityId) {
      writeError.value = error
      executionMessage.value = error instanceof HttpFailure ? `执行结果未确认（${error.code}）。请重新审阅后明确查证原意图；保留原键，不自动补发。` : error instanceof Error ? error.message : '执行结果未确认'
    }
  } finally { if (g === generation && session.identity?.id === identityId) busy.value = false }
}
/** 真实可见视口变化仅调整弹层空间，不清表单或抢焦点。 */
function resize() { viewportHeight.value = window.visualViewport?.height ?? window.innerHeight; viewportTop.value = window.visualViewport?.offsetTop ?? 0 }
window.visualViewport?.addEventListener('resize', resize); window.visualViewport?.addEventListener('scroll', resize)
window.addEventListener('resize', resize)
onBeforeRouteUpdate(() => { closeConfirmation('leave') })
onBeforeRouteLeave(() => { closeConfirmation('leave') })
watch(() => [incidentId.value, planId.value, previousId.value, session.identity?.id], () => {
  closeConfirmation('leave'); generation++; readVersion++
  incident.value = null; evidence.value = []; plan.value = null; loadError.value = null; writeError.value = null
  busy.value = false; notice.value = ''; executionMessage.value = ''; knownExecutionId.value = ''; uncertainCreate.value = false; validation.value = ''; action.value = ''; delay.value = ''; selected.value = []
  void load(true)
}, { immediate: true })
onBeforeUnmount(() => {
  closeConfirmation('leave'); generation++; readVersion++; window.clearInterval(tick)
  window.visualViewport?.removeEventListener('resize', resize); window.visualViewport?.removeEventListener('scroll', resize)
  window.removeEventListener('resize', resize)
})
</script>

<template>
  <section class="incidents-page plan-page">
    <nav class="incident-breadcrumb" aria-label="面包屑"><RouterLink to="/incidents">异常案件</RouterLink> / <RouterLink :to="`/incidents/${incidentId}`">案件</RouterLink> / 计划</nav>
    <div class="incident-page-header"><h1 ref="heading" tabindex="-1">{{ creating ? '创建恢复计划' : '恢复计划' }}</h1><button :disabled="loading || busy" @click="load()">{{ loading ? '刷新中…' : '刷新' }}</button></div>
    <RequestError :error="loadError" />
    <p v-if="loading" role="status">正在加载计划依据…</p>
    <p v-if="stale && incident" class="secondary">显示上次数据，刷新成功前不能操作</p>
    <div ref="feedback" tabindex="-1" class="plan-feedback"><RequestError :error="writeError" :message="writeMessage" /><p v-if="notice" role="status">{{ notice }}</p><p v-if="validation" role="alert" class="field-error">{{ validation }}</p></div>
    <template v-if="incident">
      <dl class="incident-metadata"><div><dt>案件 ID</dt><dd class="resource-id">{{ incidentId }}</dd></div><div><dt>事件 ID</dt><dd class="resource-id">{{ incident.event_id }}</dd></div><div><dt>订单（只读）</dt><dd>{{ scalar(target?.orderId) }}</dd></div><div><dt>权益类型（只读）</dt><dd>{{ scalar(target?.entitlementType) }}</dd></div></dl>
      <template v-if="creating">
        <section v-if="plan" class="plan-section"><h2>创建新版本的依据</h2><p>前版 v{{ plan.version }} · {{ states[plan.status] ?? `未识别状态：${plan.status}` }}</p><p class="resource-id">{{ plan.id }}</p><p class="resource-id">{{ plan.digest }}</p><p>新版本需要重新预检查和他人审批</p></section>
        <p v-if="!operator" class="secondary">仅操作员可创建计划</p>
        <form v-else class="plan-form" novalidate @submit.prevent="create">
          <fieldset :disabled="!fresh || uncertainCreate"><legend>动作（必选）</legend>
            <label v-for="option in actions" :key="option.value" class="plan-choice"><input v-model="action" type="radio" name="plan-action" :value="option.value" /><span><strong>{{ option.label }}</strong><span class="secondary">{{ option.effect }}</span></span></label>
          </fieldset>
          <div v-if="action === 'WAIT_AND_RECHECK'" class="form-field"><label for="plan-delay">等待秒数（必填，1–3600 整数）</label><input id="plan-delay" v-model="delay" type="number" min="1" max="3600" step="1" :disabled="!fresh || uncertainCreate" :aria-invalid="!!validation" aria-describedby="plan-validation" /></div>
          <fieldset :disabled="!fresh || uncertainCreate"><legend>引用证据（必选，最多 32 份）</legend>
            <p v-if="!availableEvidence.length">暂无已成功采集的证据，请返回案件采集</p>
            <label v-for="item in availableEvidence" :key="item.id" class="plan-choice"><input v-model="selected" type="checkbox" :value="item.id" /><span><span class="resource-id">{{ item.id }}</span><span>{{ item.source }} · {{ dateTime(item.collected_at) }}</span><span class="resource-id">{{ item.content_sha256 }}</span></span></label>
          </fieldset>
          <p id="plan-validation" v-if="validation" class="field-error">{{ validation }}</p>
          <button type="submit" class="primary-button" :disabled="!fresh || uncertainCreate || !availableEvidence.length">{{ busy ? '创建中…' : '创建草稿' }}</button>
        </form>
      </template>
      <template v-else-if="plan">
        <p class="plan-state">{{ states[plan.status] ?? `未识别状态：${plan.status}` }}</p>
        <dl class="incident-metadata"><div><dt>计划 ID</dt><dd class="resource-id">{{ plan.id }}</dd></div><div><dt>准确版本</dt><dd>v{{ plan.version }}</dd></div><div><dt>计划 SHA-256</dt><dd class="resource-id">{{ plan.digest }}</dd></div><div><dt>配置 SHA-256</dt><dd class="resource-id">{{ plan.configurationDigest }}</dd></div><div><dt>创建时间</dt><dd>{{ dateTime(plan.createdAt) }}</dd></div><div><dt>有效期至</dt><dd>{{ dateTime(plan.expiresAt) }}</dd></div></dl>
        <p v-if="planTimedOut && plan.status !== 'EXPIRED'" class="secondary">计划期限已到，请刷新核对服务端状态</p>
        <div class="plan-layout">
          <div class="plan-primary">
            <section class="plan-section"><h2>动作与参数</h2><p class="plan-action-title">{{ actions.find(a => a.value === plan?.action)?.label }}</p><p>{{ actions.find(a => a.value === plan?.action)?.effect }}</p><dl class="incident-metadata"><div><dt>动作</dt><dd>{{ plan.action }}</dd></div><div><dt>等待秒数</dt><dd>{{ plan.action === 'WAIT_AND_RECHECK' ? plan.parameters.delaySeconds : '不适用' }}</dd></div><div><dt>动作参数 SHA-256</dt><dd class="resource-id">{{ plan.actionDigest }}</dd></div><div><dt>原事件 SHA-256</dt><dd class="resource-id">{{ plan.eventDigest }}</dd></div></dl></section>
            <section class="plan-section"><h2>证据引用</h2><article v-for="eid in plan.evidenceIds" :key="eid" class="plan-evidence"><h3 class="resource-id">{{ eid }}</h3><template v-for="item in evidence.filter(e => e.id === eid)" :key="item.id"><p>{{ item.source }} · {{ dateTime(item.collected_at) }} · {{ item.outcome }}</p><p class="resource-id">{{ item.content_sha256 }}</p><details><summary>展开证据 JSON</summary><JsonDocument :text="item.content" label="证据内容" /></details></template></article></section>
            <section class="plan-section"><h2>预检查记录</h2><p>预检查通过只允许申请审批，不代表已批准或恢复成功</p><p v-if="!plan.prechecks.length">暂无预检查</p>
              <article v-for="check in plan.prechecks" :key="check.id" class="plan-evidence"><h3>{{ check.passed ? '预检查通过' : '预检查未通过' }}</h3><dl class="incident-metadata"><div><dt>检查 ID</dt><dd class="resource-id">{{ check.id }}</dd></div><div><dt>采集时间</dt><dd>{{ dateTime(check.createdAt) }}</dd></div><div><dt>检查有效期至</dt><dd>{{ dateTime(check.expiresAt) }}{{ Date.parse(check.expiresAt) <= now ? '（已到期）' : '' }}</dd></div><div><dt>只读事实一致性</dt><dd>{{ check.businessVerified ? '一致' : '未满足' }}</dd></div></dl><ul v-if="check.reasons.length"><li v-for="reason in check.reasons" :key="reason">{{ reasons[reason] ?? reason }} <span class="secondary">{{ reason }}</span></li></ul><details><summary>展开查询事实 JSON</summary><JsonDocument :text="JSON.stringify(check.facts, null, 2)" label="预检查事实" /></details></article>
            </section>
          </div>
          <aside class="plan-review"><section class="plan-section"><h2>审批记录</h2><dl class="incident-metadata"><div><dt>申请人 ID</dt><dd class="resource-id">{{ plan.requesterId }}</dd></div><div><dt>原始状态</dt><dd>{{ states[plan.recordedStatus] ?? plan.recordedStatus }}</dd></div></dl><p v-if="!plan.approvalRequests.length">尚未提交审批</p><div v-for="item in plan.approvalRequests" :key="item.precheck_id" class="plan-evidence"><p>提交时间：{{ dateTime(item.created_at) }}</p><p>绑定检查 ID</p><p class="resource-id">{{ item.precheck_id }}</p></div><p v-if="!plan.decisions.length">暂无审批决定</p><article v-for="item in plan.decisions" :key="item.approver_id" class="plan-evidence"><h3>{{ item.decision === 'APPROVE' ? '◇ 已批准' : '× 已拒绝' }}</h3><p>审批人 ID</p><p class="resource-id">{{ item.approver_id }}</p><p>{{ dateTime(item.created_at) }}</p><p>批准依据 v{{ plan.version }} / SHA-256</p><p class="resource-id">{{ item.digest }}</p></article></section>
            <section class="plan-section"><h2>当前操作</h2>
              <template v-if="plan.status === 'DRAFT'"><p v-if="!operator">仅操作员可预检查</p><p v-else-if="plan.requesterId !== session.identity?.id">仅本版本申请人可提交审批</p><p v-else-if="!checkFresh">提交审批需要本版最新通过且未过期的预检查</p><div class="plan-actions"><button :disabled="!canPrecheck" @click="mutate('PRECHECK', plan)">{{ busy ? '处理中…' : '预检查' }}</button><button class="primary-button" :disabled="!canSubmit" @click="openConfirmation('SUBMIT')">提交审批</button></div></template>
              <template v-else-if="plan.status === 'PENDING_APPROVAL'"><p v-if="plan.requesterId === session.identity?.id">不能审批本人提交的计划</p><p v-else-if="session.identity?.role !== 'APPROVER'">等待其他审批人审查此版本</p><p v-if="!checkFresh">申请绑定的预检查已过期，需要创建新版本</p><div class="plan-actions"><button class="primary-button" :disabled="!canDecide" @click="openConfirmation('APPROVE')">批准此版本</button><button :disabled="!canDecide" @click="openConfirmation('REJECT')">拒绝此版本</button></div></template>
              <template v-else-if="plan.status === 'APPROVED'"><p>批准不代表恢复成功</p><p v-if="!operator">仅操作员可执行</p><button class="primary-button" :disabled="!canExecute" @click="openConfirmation('EXECUTE')">执行计划 / 查证原意图</button><p v-if="executionMessage" role="status">{{ executionMessage }}</p><RouterLink v-if="knownExecutionId" :to="`/incidents/${incidentId}/executions/${knownExecutionId}`">回查已知执行 {{ knownExecutionId }}</RouterLink></template>
              <button v-if="plan.status !== 'APPROVED' && operator" :disabled="!canExecute" @click="openConfirmation('EXECUTE')">查证原执行意图</button>
              <RouterLink v-if="operator && fresh" :to="{ path: `/incidents/${incidentId}/plans/new`, query: { previous: plan.id } }" class="plan-new-version">创建新版本</RouterLink>
            </section>
          </aside>
        </div>
      </template>
      <section class="plan-section"><h2>原始载荷（只读）</h2><details><summary>展开不可变原始事件</summary><JsonDocument :text="incident.original_event" label="原始事件" /></details></section>
    </template>
    <dialog ref="dialog" class="plan-dialog" :class="{ 'plan-dialog-low': viewportHeight <= 480 }" :style="{ '--plan-viewport-height': `${viewportHeight}px`, '--plan-viewport-top': `${viewportTop}px` }" aria-labelledby="plan-confirm-title" @keydown="trapConfirmationFocus" @cancel.prevent="closeConfirmation()" @close="onConfirmationClosed">
      <template v-if="confirmation"><header><h2 id="plan-confirm-title" ref="dialogHeading" tabindex="-1">{{ operationLabel }}</h2><button type="button" aria-label="关闭确认" @click="closeConfirmation()">关闭</button></header><div class="plan-dialog-content"><p>请审阅准确版本的动作和审批依据</p><template v-if="confirmation.operation === 'EXECUTE'"><p>默认查证原意图。旧键丢失时不能重新发送。</p><label class="plan-choice"><input v-model="firstExecution" type="checkbox" :disabled="!canStartExecution" /><span>这是此身份对此版本的首次执行（此前未提交）</span></label></template><dl class="incident-metadata"><div><dt>动作</dt><dd>{{ confirmation.plan.action }}</dd></div><div><dt>订单 / 权益</dt><dd>{{ scalar(target?.orderId) }} / {{ scalar(target?.entitlementType) }}</dd></div><div><dt>版本</dt><dd>v{{ confirmation.plan.version }}</dd></div><div><dt>计划 ID</dt><dd class="resource-id">{{ confirmation.plan.id }}</dd></div><div><dt>完整计划摘要</dt><dd class="resource-id">{{ confirmation.plan.digest }}</dd></div><div><dt>配置摘要</dt><dd class="resource-id">{{ confirmation.plan.configurationDigest }}</dd></div><div><dt>参数</dt><dd>{{ JSON.stringify(confirmation.plan.parameters) }}</dd></div><div><dt>有效期至</dt><dd>{{ dateTime(confirmation.plan.expiresAt) }}</dd></div></dl><p v-if="!confirmationAllowed" role="status">当前状态或检查期限已变化，请关闭后刷新审阅</p></div><footer class="plan-actions"><button @click="closeConfirmation()">取消</button><button class="primary-button" :disabled="!confirmationAllowed" @click="confirm">{{ operationLabel }}</button></footer></template>
    </dialog>
  </section>
</template>

<style scoped>
.plan-page { overflow-wrap: anywhere; }
.plan-page h1 { font-size: 15px; line-height: 24px; font-weight: 600; }
.plan-feedback:focus { outline: none; }
.plan-state, .plan-action-title { font-size: 15px; line-height: 24px; font-weight: 600; margin-bottom: 12px; }
.plan-section { border-bottom: 1px solid var(--ov-border); padding-bottom: 24px; margin-bottom: 24px; min-width: 0; }
.plan-section p + p { margin-top: 8px; }
.plan-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 32px; }
.plan-primary, .plan-review { min-width: 0; }
.plan-review { border-left: 1px solid var(--ov-border); padding-left: 24px; }
.plan-review .incident-metadata { grid-template-columns: minmax(0, 1fr); }
.plan-evidence { padding-block: 16px; border-bottom: 1px solid var(--ov-border); }
.plan-evidence:last-child { border: 0; }
.plan-form { max-width: 800px; }
.plan-form fieldset { border: 0; padding: 0; margin: 0 0 24px; min-width: 0; }
.plan-form legend { font-size: 17px; line-height: 26px; font-weight: 600; margin-bottom: 12px; }
.plan-choice { display: flex; gap: 12px; align-items: flex-start; padding: 8px 4px; cursor: pointer; min-height: 44px; }
.plan-choice input { width: 20px; height: 20px; flex: none; margin: 2px 0; accent-color: var(--ov-primary); }
.plan-choice > span { min-width: 0; }
.plan-choice span span { display: block; }
.plan-choice strong { font-size: 15px; font-weight: 600; }
.plan-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 16px; }
.plan-new-version { display: inline-block; padding-block: 12px; min-height: 44px; }
.plan-dialog { padding: 0; color: var(--ov-text); background: var(--ov-surface); border: 1px solid var(--ov-border); border-radius: var(--ov-radius-md); width: min(520px, calc(100vw - 32px)); max-width: none; max-height: calc(var(--plan-viewport-height) - 32px); max-height: min(calc(100dvh - 32px), calc(var(--plan-viewport-height) - 32px)); margin: auto; top: var(--plan-viewport-top); box-shadow: var(--ov-shadow-overlay); }
.plan-dialog[open] { display: flex; flex-direction: column; }
.plan-dialog::backdrop { background: #0008; }
.plan-dialog header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.plan-dialog header h2 { font-size: 17px; line-height: 26px; font-weight: 600; margin: 0; }
.plan-dialog header h2:focus { outline: none; }
.plan-dialog-content { padding: 0 16px; min-height: 0; overflow-y: auto; }
.plan-dialog .incident-metadata { grid-template-columns: minmax(0, 1fr); }
.plan-dialog footer { margin: 0; padding: 16px; border-top: 1px solid var(--ov-border); }
@media (width < 1280px) { .plan-layout { display: block; } .plan-review { border-left: 0; padding-left: 0; } }
@media (width < 768px) {
  .plan-actions { flex-direction: column; } .plan-actions button, .plan-form > button { width: 100%; }
  .plan-choice { padding-block: 12px; }
}
.plan-dialog-low { overflow-y: auto; }
.plan-dialog-low[open] { display: block; }
.plan-dialog-low .plan-dialog-content { overflow: visible; }
.plan-dialog, .plan-dialog-content { overscroll-behavior: contain; }
</style>
