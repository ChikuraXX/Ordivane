<script setup lang="ts">
import { computed } from 'vue'
import { HttpFailure } from '../lib/http/client'
/** 安全错误展示，不输出内部异常或请求信息。 */
const props = defineProps<{ error: unknown; message?: string }>()
const failure = computed(() => props.error instanceof HttpFailure ? props.error : null)
</script>

<template>
  <div v-if="error" class="request-error" role="alert" tabindex="-1">
    <p>{{ message || failure?.message || '页面暂不可用，请重试' }}</p>
    <p v-if="failure?.traceId" class="trace">追踪 ID：{{ failure.traceId }}</p>
  </div>
</template>
