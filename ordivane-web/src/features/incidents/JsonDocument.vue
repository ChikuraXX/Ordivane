<script setup lang="ts">
import { computed, ref } from 'vue'
import { jsonObject } from './api'
/** 纯文本完整展示规范JSON，不执行HTML、不重新序列化精确数值。 */
const props = defineProps<{ text: string | null; label: string }>()
const valid = computed(() => props.text !== null && jsonObject(props.text) !== null)
const copied = ref('')
/** Clipboard拒绝时保留全文供用户选择，不虚报成功。 */
async function copy() {
  if (props.text === null) return
  try { await navigator.clipboard.writeText(props.text); copied.value = '已复制' } catch { copied.value = '复制失败，请选择文本复制' }
}
</script>
<template>
  <div class="incident-json">
    <p v-if="text === null">未提供正文</p>
    <template v-else>
      <p v-if="!valid" class="field-error">JSON 格式无效，以下为原始文本</p>
      <pre tabindex="0" :aria-label="label">{{ text }}</pre>
      <button type="button" @click="copy">复制{{ label }}</button><span class="secondary" role="status">{{ copied }}</span>
    </template>
  </div>
</template>
