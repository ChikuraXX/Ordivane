<script setup lang="ts">
import { onBeforeUnmount, onErrorCaptured, onMounted, ref } from 'vue'
import HeaderControls from './components/HeaderControls.vue'
import RequestError from './components/RequestError.vue'
import { useSession } from './features/auth/session'
import { setUnauthorizedHandler } from './lib/http/client'
import { router } from './router'
import { observeSystemTheme } from './styles/theme'

const session = useSession()
const renderError = ref(false)
let stopTheme: (() => void) | undefined

/** 认证边界统一清状态及跳转，不补发原写请求。 */
setUnauthorizedHandler(() => {
  session.invalidate()
  void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
})
/** 前台恢复时重新询问服务端，避免客户端计时冒充会话失效。 */
async function revalidate() {
  if (document.visibilityState !== 'visible' || !session.authenticated || session.busy) return
  await session.restore(true)
  if (!session.authenticated && !session.recoveryError) await router.replace('/login')
}
/** 恢复失败只允许用户显式重查，不自动重发登录或退出。 */
async function retry() {
  await session.restore(true)
  if (!session.recoveryError) await router.replace(session.authenticated ? '/' : '/login')
}
onErrorCaptured(() => { renderError.value = true; return false })
onMounted(() => {
  stopTheme = observeSystemTheme()
  window.addEventListener('focus', revalidate)
  window.addEventListener('pageshow', revalidate)
  document.addEventListener('visibilitychange', revalidate)
})
onBeforeUnmount(() => {
  stopTheme?.()
  window.removeEventListener('focus', revalidate)
  window.removeEventListener('pageshow', revalidate)
  document.removeEventListener('visibilitychange', revalidate)
})
</script>

<template>
  <div v-if="!session.ready" class="startup-state"><main aria-busy="true" role="status">正在恢复会话…</main></div>
  <div v-else-if="session.recoveryError || renderError" class="startup-state">
    <div class="login-tools"><HeaderControls /></div>
    <main>
      <h1>Ordivane</h1>
      <template v-if="renderError"><p role="alert">页面暂不可用，请刷新</p><button type="button" @click="router.go(0)">刷新</button></template>
      <template v-else><RequestError :error="session.recoveryError" /><button type="button" @click="retry">重试</button></template>
    </main>
  </div>
  <RouterView v-else />
</template>
