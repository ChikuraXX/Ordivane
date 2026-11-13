<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import RequestError from '../../components/RequestError.vue'
import { useSession } from '../auth/session'
import { HttpFailure, isRecord } from '../../lib/http/client'
import { collectEvidence, dateTime, getEvidence, getIncident, jsonObject, scalar } from './api'
import type { Evidence, IncidentDetail, Observation } from './api'
import JsonDocument from './JsonDocument.vue'

const route = useRoute()
const session = useSession()
const incident = ref<IncidentDetail | null>(null)
const evidence = ref<Evidence[] | null>(null)
const detailError = ref<unknown>(null)
const evidenceError = ref<unknown>(null)
const collectionError = ref<unknown>(null)
const loading = ref(false)
const evidenceLoading = ref(false)
const collecting = ref(false)
const collectionNotice = ref('')
const updatedAt = ref('')
const heading = ref<HTMLElement | null>(null)
const collectionResult = ref<HTMLElement | null>(null)
let generation = 0
let detailVersion = 0
let evidenceVersion = 0
const id = computed(() => typeof route.params.incidentId === 'string' ? route.params.incidentId : '')
const original = computed(() => jsonObject(incident.value?.original_event ?? null))
const latestReport = computed(() => incident.value?.observations.filter(item => item.kind === 'REPORT').at(-1))
const title = computed(() => {
  const body = jsonObject(latestReport.value?.body ?? null)
  return typeof body?.errorSummary === 'string' && body.errorSummary ? body.errorSummary : '案件'
})
const canCollect = computed(() => session.identity?.role === 'OPERATOR' && incident.value?.status === 'OPEN')
/** 已接收事实组成时间线，不生成未来计划或执行记录。 */
const timeline = computed(() => [
  ...(incident.value?.observations.map(item => ({ id: item.id, at: item.received_at, text: item.kind === 'REPORT' ? '收到失败报告' : item.kind === 'RAW_DLX' ? '收到原文死信观察' : '隔离观察', target: `observation-${item.id}` })) ?? []),
  ...(evidence.value?.map(item => ({ id: item.id, at: item.collected_at, text: item.outcome === 'COLLECTED' ? '已采集证据快照' : item.outcome === 'DEPENDENCY_FAILED' ? '证据采集依赖失败' : '未识别采集结果', target: `evidence-${item.id}` })) ?? []),
].sort((a, b) => a.at.localeCompare(b.at) || a.id.localeCompare(b.id)))
/** 观察摘要只提取已接受报告字段，保留原始正文供核对。 */
function reportField(item: Observation, field: string) { return scalar(jsonObject(item.body)?.[field]) }
/** 权益对象缺失与active=false保持区分。 */
function entitlementActive(snapshot: Evidence) {
  const entitlement = jsonObject(snapshot.content)?.entitlement
  return scalar(isRecord(entitlement) ? entitlement.active : entitlement)
}
/** 详情刷新失败保留同案上次数据；切案先清空，拒绝旧响应覆盖。 */
async function loadDetail(focus = false) {
  const current = ++detailVersion
  const currentGeneration = generation
  const requestedId = id.value
  loading.value = true; detailError.value = null
  try {
    const value = await getIncident(requestedId)
    if (current !== detailVersion || currentGeneration !== generation) return
    incident.value = value; updatedAt.value = new Date().toISOString()
    if (focus) { await nextTick(); heading.value?.focus() }
  } catch (failure) {
    if (current === detailVersion && currentGeneration === generation) {
      detailError.value = failure
      if (failure instanceof HttpFailure && [401, 403, 404].includes(failure.status)) { incident.value = null; evidence.value = null; evidenceVersion++ }
    }
  }
  finally { if (current === detailVersion && currentGeneration === generation) loading.value = false }
}
/** 同案多次历史GET也按序号隔离；错误不能冒充未采集。 */
async function loadEvidence() {
  const current = ++evidenceVersion
  const currentGeneration = generation
  const requestedId = id.value
  evidenceLoading.value = true; evidenceError.value = null
  try {
    const value = await getEvidence(requestedId)
    if (current === evidenceVersion && currentGeneration === generation) evidence.value = value
  } catch (failure) {
    if (current === evidenceVersion && currentGeneration === generation) {
      evidenceError.value = failure
      if (failure instanceof HttpFailure && [401, 403, 404].includes(failure.status)) evidence.value = null
    }
  }
  finally { if (current === evidenceVersion && currentGeneration === generation) evidenceLoading.value = false }
}
/** 同步写不重试；200和503后均重新GET历史，错误与失败快照并存。 */
async function collect() {
  if (collecting.value || !canCollect.value) return
  const currentGeneration = generation
  const requestedId = id.value
  collecting.value = true; collectionError.value = null; collectionNotice.value = ''
  try {
    await collectEvidence(requestedId)
    if (currentGeneration === generation) collectionNotice.value = '证据采集完成'
  } catch (failure) { if (currentGeneration === generation) collectionError.value = failure }
  finally {
    if (currentGeneration === generation) {
      await loadEvidence()
      if (currentGeneration === generation) {
        collecting.value = false
        await nextTick(); collectionResult.value?.focus()
      }
    }
  }
}
/** 切换资源后立即清除上案敏感对象，卸载同时使全部异步回调失效。 */
watch(id, () => {
  generation++; detailVersion++; evidenceVersion++
  incident.value = null; evidence.value = null; updatedAt.value = ''
  detailError.value = null; evidenceError.value = null; collectionError.value = null
  collecting.value = false; collectionNotice.value = ''
  void loadDetail(true); void loadEvidence()
}, { immediate: true })
onBeforeUnmount(() => { generation++; detailVersion++; evidenceVersion++ })
</script>

<template>
  <section class="incidents-page">
    <nav aria-label="面包屑" class="incident-breadcrumb"><RouterLink :to="{ path: '/incidents', query: route.query }">异常案件</RouterLink><span> / 案件</span></nav>
    <div class="incident-page-header"><h1 ref="heading" tabindex="-1">{{ title }}</h1><button type="button" :disabled="loading" @click="loadDetail()">{{ loading ? '刷新中…' : '刷新案件' }}</button></div>
    <RequestError :error="detailError" />
    <p v-if="loading" role="status">正在加载案件…</p>
    <p v-if="detailError && incident" class="secondary">显示上次详情，数据可能已过时</p>
    <button v-if="detailError" type="button" @click="loadDetail(true)">重试详情</button>
    <template v-if="incident">
      <p class="incident-status">{{ incident.status === 'OPEN' ? '● 待调查' : '✓ 已解决' }}</p>
      <RouterLink v-if="session.identity?.role === 'OPERATOR' && !detailError && !loading" :to="`/incidents/${incident.id}/plans/new`" class="incident-title">创建计划</RouterLink>
      <dl class="incident-metadata">
        <div><dt>案件 ID</dt><dd class="resource-id">{{ incident.id }}</dd></div>
        <div><dt>事件 ID</dt><dd class="resource-id">{{ incident.event_id }}</dd></div>
        <div><dt>来源</dt><dd>{{ incident.source }}</dd></div><div><dt>处理器</dt><dd>{{ incident.handler_id }}</dd></div>
        <div><dt>建案时间</dt><dd><time :datetime="incident.created_at" :title="incident.created_at">{{ dateTime(incident.created_at) }}</time></dd></div>
      </dl>
      <section v-if="incident.status === 'RESOLVED'" aria-label="案件解决依据"><h2>解决依据</h2><dl class="incident-metadata"><div><dt>解决时间</dt><dd>{{ incident.resolved_at === null ? '无值' : dateTime(incident.resolved_at) }}</dd></div><div><dt>解决执行 ID</dt><dd class="resource-id"><RouterLink v-if="incident.resolved_execution_id" :to="`/incidents/${incident.id}/executions/${incident.resolved_execution_id}`">{{ incident.resolved_execution_id }}</RouterLink><span v-else>无值</span></dd></div><div><dt>解决观测 ID</dt><dd class="resource-id">{{ incident.resolution_observation_id ?? '无值' }}</dd></div></dl></section>
      <nav class="incident-sections" aria-label="案件分区"><a href="#evidence">证据</a></nav>
      <div class="incident-investigation">
        <div class="incident-primary">
          <section aria-labelledby="original-heading"><h2 id="original-heading">原始事件</h2>
            <dl class="incident-metadata"><div v-for="field in ['eventType', 'schemaVersion', 'occurredAt']" :key="field"><dt>{{ field === 'occurredAt' ? '事件发生时间（原值）' : field }}</dt><dd>{{ scalar(original?.[field]) }}</dd></div><div><dt>事件 SHA-256</dt><dd class="resource-id">{{ incident.event_sha256 }}</dd></div></dl>
            <details><summary>展开原始事件 JSON</summary><JsonDocument :text="incident.original_event" label="原始事件" /></details>
          </section>
          <section aria-labelledby="observations-heading"><h2 id="observations-heading">失败观察</h2>
            <p v-if="!incident.observations.length">暂无失败观察</p>
            <article v-for="item in incident.observations" :id="`observation-${item.id}`" :key="item.id" class="incident-observation">
              <h3>{{ item.kind === 'REPORT' ? reportField(item, 'errorSummary') : item.kind === 'RAW_DLX' ? '原文死信观察' : `观察：${item.kind}` }}</h3>
              <dl class="incident-metadata"><div><dt>观察记录 ID</dt><dd class="resource-id">{{ item.id }}</dd></div><div><dt>接收时间</dt><dd><time :datetime="item.received_at" :title="item.received_at">{{ dateTime(item.received_at) }}</time></dd></div><div><dt>事件正文冲突</dt><dd>{{ item.event_conflict ? '是（保留首次事件快照）' : '否' }}</dd></div>
                <template v-if="item.kind === 'REPORT'"><div><dt>错误码</dt><dd>{{ reportField(item, 'errorCode') }}</dd></div><div><dt>观察发生时间（原值）</dt><dd>{{ reportField(item, 'observedAt') }}</dd></div><div><dt>关联执行 ID</dt><dd class="resource-id">{{ reportField(item, 'executionId') }}</dd></div><div><dt>报告 ID</dt><dd class="resource-id">{{ scalar(item.report_id) }}</dd></div><div><dt>失败观察 ID</dt><dd class="resource-id">{{ scalar(item.observation_id) }}</dd></div></template>
              </dl>
              <p class="secondary">未提供异常栈</p>
              <details><summary>展开观察正文及摘要</summary><p class="resource-id">SHA-256：{{ item.body_sha256 }}</p><JsonDocument :text="item.body" label="观察正文" /></details>
            </article>
          </section>
          <section id="evidence" aria-labelledby="evidence-heading">
            <div class="incident-section-header"><h2 id="evidence-heading">证据历史</h2><div class="incident-actions"><button type="button" :disabled="evidenceLoading || collecting" @click="loadEvidence">刷新历史</button><button class="primary-button" type="button" :disabled="!canCollect || collecting || loading || !!detailError" @click="collect">{{ collecting ? '采集中…' : '采集证据' }}</button></div></div>
            <p v-if="!canCollect" class="secondary">{{ session.identity?.role !== 'OPERATOR' ? '仅操作员可采集证据' : '当前案件状态不支持采集证据' }}</p>
            <div ref="collectionResult" class="incident-collection-result" tabindex="-1"><RequestError :error="collectionError" /><p v-if="collectionNotice" role="status">{{ collectionNotice }}</p></div>
            <RequestError :error="evidenceError" /><p v-if="evidenceError && evidence" class="secondary">显示上次证据历史，数据可能已过时</p>
            <p v-if="evidenceLoading" role="status">正在加载证据历史…</p>
            <p v-if="evidence && !evidence.length">暂无证据快照（未采集）</p>
            <article v-for="snapshot in evidence" :id="`evidence-${snapshot.id}`" :key="snapshot.id" class="incident-evidence">
              <h3 :class="{ 'field-error': snapshot.outcome !== 'COLLECTED' }">{{ snapshot.outcome === 'COLLECTED' ? '已采集' : snapshot.outcome === 'DEPENDENCY_FAILED' ? '依赖查询失败' : `未识别结果：${snapshot.outcome}` }}</h3>
              <dl class="incident-metadata"><div><dt>快照 ID</dt><dd class="resource-id">{{ snapshot.id }}</dd></div><div><dt>来源</dt><dd>{{ snapshot.source }}</dd></div><div><dt>采集时间</dt><dd><time :datetime="snapshot.collected_at" :title="snapshot.collected_at">{{ dateTime(snapshot.collected_at) }}</time></dd></div><div><dt>内容 SHA-256</dt><dd class="resource-id">{{ snapshot.content_sha256 }}</dd></div></dl>
              <template v-if="snapshot.outcome === 'COLLECTED'">
                <dl class="incident-metadata"><div><dt>当前事实一致性核验</dt><dd>{{ jsonObject(snapshot.content)?.businessVerified === true ? '一致' : jsonObject(snapshot.content)?.businessVerified === false ? '未满足' : '未提供' }}</dd></div><div><dt>权益 active</dt><dd>{{ entitlementActive(snapshot) }}</dd></div><div><dt>处理记录上限</dt><dd>{{ scalar(jsonObject(snapshot.content)?.processingRecordLimit) }}</dd></div><div><dt>历史是否完整</dt><dd>{{ scalar(jsonObject(snapshot.content)?.historyComplete) }}</dd></div></dl>
              </template>
              <details><summary>展开完整证据 JSON</summary><JsonDocument :text="snapshot.content" label="证据内容" /></details>
            </article>
          </section>
        </div>
        <aside class="incident-timeline" aria-labelledby="timeline-heading"><details open><summary id="timeline-heading">案件时间线</summary><ol><li v-for="item in timeline" :key="item.id"><a :href="`#${item.target}`">{{ item.text }}</a><time :datetime="item.at" :title="item.at">{{ dateTime(item.at) }}</time></li></ol></details></aside>
      </div>
      <p v-if="updatedAt" class="secondary">详情更新于 {{ dateTime(updatedAt) }}</p>
    </template>
  </section>
</template>
