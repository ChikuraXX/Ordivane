<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RequestError from '../../components/RequestError.vue'
import { dateTime, listIncidents } from './api'
import type { Filters, IncidentPage } from './api'

const route = useRoute()
const router = useRouter()
const result = ref<IncidentPage | null>(null)
const error = ref<unknown>(null)
const loading = ref(false)
const source = ref('')
const status = ref('')
const size = ref('20')
const fieldError = ref('')
const expanded = ref(false)
const resultsHeading = ref<HTMLElement | null>(null)
const filterButton = ref<HTMLButtonElement | null>(null)
const updatedAt = ref('')
let version = 0
let activeKey = ''
/** 站内查询仅接受已支持值，非法深链回到明确默认值。 */
const filters = computed<Filters>(() => ({
  page: typeof route.query.page === 'string' && /^\d+$/.test(route.query.page) && Number(route.query.page) <= 100000 ? Number(route.query.page) : 0,
  size: typeof route.query.size === 'string' && /^\d+$/.test(route.query.size) && Number(route.query.size) >= 1 && Number(route.query.size) <= 100 ? Number(route.query.size) : 20,
  source: typeof route.query.source === 'string' && /^[A-Za-z0-9_.-]{1,128}$/.test(route.query.source) ? route.query.source : '',
  status: route.query.status === 'OPEN' || route.query.status === 'RESOLVED' ? route.query.status : '',
}))
/** 当前请求版本屏障同时覆盖切页、筛选、刷新及卸载。 */
async function load(focus = false) {
  const current = ++version
  const query = { ...filters.value }
  const key = JSON.stringify(query)
  if (key !== activeKey) { result.value = null; updatedAt.value = ''; activeKey = key }
  loading.value = true
  error.value = null
  try {
    const response = await listIncidents(query)
    if (current !== version) return
    result.value = response
    updatedAt.value = new Date().toISOString()
    if (focus) { expanded.value = false; await nextTick(); resultsHeading.value?.focus() }
  } catch (failure) { if (current === version) { error.value = failure; expanded.value = true } }
  finally { if (current === version) loading.value = false }
}
/** 显式应用筛选，返回首屏；相同条件也允许重新查询。 */
async function apply(reset = false) {
  if (reset) { source.value = ''; status.value = ''; size.value = '20' }
  fieldError.value = ''
  if (source.value !== '' && !/^[A-Za-z0-9_.-]{1,128}$/.test(source.value)) {
    fieldError.value = '来源须为1–128位字母、数字、下划线、点或连字符'
    expanded.value = true
    await nextTick(); document.getElementById('incident-source')?.focus(); return
  }
  const query = { page: '0', size: size.value, ...(source.value ? { source: source.value } : {}), ...(status.value ? { status: status.value } : {}) }
  const target = router.resolve({ path: '/incidents', query })
  if (target.fullPath === route.fullPath) await load(true)
  else await router.push(target.fullPath)
}
/** 翻页沿用已应用条件，不使用尚未提交的筛选草稿。 */
function pageTo(page: number) { void router.push({ path: '/incidents', query: { ...route.query, page: String(page) } }) }
/** 收起先恢复焦点，不将焦点遗留在隐藏控件。 */
function toggleFilters() { if (expanded.value) filterButton.value?.focus(); expanded.value = !expanded.value }
watch(filters, () => {
  source.value = filters.value.source; status.value = filters.value.status; size.value = String(filters.value.size)
  void load(true)
}, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>

<template>
  <section class="incidents-page">
    <div class="incident-page-header"><h1>异常案件</h1><button type="button" :disabled="loading" @click="load()">{{ loading ? '刷新中…' : '刷新' }}</button></div>
    <button ref="filterButton" class="incident-filter-toggle" type="button" :aria-expanded="expanded" aria-controls="incident-filters" @click="toggleFilters">{{ expanded ? '收起筛选' : '筛选' }}</button>
    <p class="secondary incident-applied">已应用：{{ filters.status === 'OPEN' ? '待调查' : filters.status === 'RESOLVED' ? '已解决' : '全部状态' }} · {{ filters.source || '全部来源' }}</p>
    <div id="incident-filters" class="incident-filter-region" :class="{ expanded }">
      <form class="incident-filters" @submit.prevent="apply()">
        <div><label for="incident-status">状态</label><select id="incident-status" v-model="status"><option value="">全部</option><option value="OPEN">待调查</option><option value="RESOLVED">已解决</option></select></div>
        <div><label for="incident-source">来源（精确匹配）</label><input id="incident-source" v-model="source" :aria-invalid="!!fieldError" :aria-describedby="fieldError ? 'source-error' : undefined" autocomplete="off"><p v-if="fieldError" id="source-error" class="field-error" role="alert">{{ fieldError }}</p></div>
        <div><label for="incident-size">每页条数</label><select id="incident-size" v-model="size"><option v-if="![20, 50, 100].includes(filters.size)" :value="String(filters.size)">{{ filters.size }}</option><option value="20">20</option><option value="50">50</option><option value="100">100</option></select></div>
        <div class="incident-actions"><button type="submit" class="primary-button">查询</button><button type="button" @click="apply(true)">重置</button></div>
      </form>
    </div>
    <RequestError :error="error" />
    <p v-if="error && result" class="secondary">显示上次结果，数据可能已过时</p>
    <h2 ref="resultsHeading" class="incident-results-heading" tabindex="-1">案件列表</h2>
    <p v-if="loading" role="status">正在加载案件…</p>
    <template v-if="result">
      <p v-if="!result.items.length" role="status">{{ filters.source || filters.status ? '没有匹配的案件' : '暂无案件' }}</p>
      <div v-else class="incident-table-region" role="region" aria-label="案件列表" tabindex="0">
        <div class="incident-table-head" aria-hidden="true"><span>案件</span><span>状态</span><span>来源 / 处理器</span><span>建案时间</span></div>
        <ul class="incident-list">
          <li v-for="item in result.items" :key="item.id" class="incident-row">
            <div><RouterLink class="incident-title" :to="{ path: `/incidents/${item.id}`, query: route.query }">案件 {{ item.id }}</RouterLink><p class="secondary resource-id incident-id">{{ item.id }}</p></div>
            <p class="incident-status">{{ item.status === 'OPEN' ? '● 待调查' : '✓ 已解决' }}</p>
            <div class="incident-source"><p>{{ item.source }}</p><p class="secondary">{{ item.handler_id }}</p></div>
            <time :datetime="item.created_at" :title="item.created_at">{{ dateTime(item.created_at) }}</time>
          </li>
        </ul>
      </div>
      <nav class="incident-pagination" aria-label="案件分页"><span>共 {{ result.total }} 条 · 第 {{ result.page + 1 }} 页</span><div class="incident-actions"><button type="button" :disabled="result.page === 0" @click="pageTo(result.page - 1)">上一页</button><button type="button" :disabled="(result.page + 1) * result.size >= result.total || result.page >= 100000" @click="pageTo(result.page + 1)">下一页</button></div></nav>
      <p v-if="updatedAt" class="secondary">更新于 {{ dateTime(updatedAt) }}</p>
    </template>
    <button v-else-if="error" type="button" @click="load()">重试查询</button>
  </section>
</template>
