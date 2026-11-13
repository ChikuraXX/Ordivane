<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import HeaderControls from '../components/HeaderControls.vue'
import RequestError from '../components/RequestError.vue'
import { useSession } from '../features/auth/session'

const session = useSession()
const router = useRouter()
const route = useRoute()
const error = ref<unknown>(null)
const errorRegion = ref<HTMLElement | null>(null)

/** 服务端退出失败时保留身份，并显示可追踪错误，不自动重发。 */
async function submitLogout() {
  error.value = null
  try {
    await session.logout()
    await router.replace('/login')
  } catch (failure) {
    if (!session.authenticated && !session.recoveryError) {
      await router.replace('/login')
      return
    }
    error.value = failure
    await nextTick()
    errorRegion.value?.querySelector<HTMLElement>('.request-error')?.focus()
  }
}
</script>

<template>
  <div v-if="session.identity" class="app-shell">
    <a class="skip-link" href="#main-content">跳至正文</a>
    <header class="app-header">
      <span class="brand">Ordivane</span>
      <nav class="incident-global-nav" aria-label="主导航"><RouterLink :to="{ path: '/incidents', query: route.query }" :aria-current="route.name === 'incidents' ? 'page' : undefined"><span class="incident-nav-desktop">异常案件</span><span class="incident-nav-mobile">案件</span></RouterLink></nav>
      <HeaderControls account @logout="submitLogout" />
    </header>
    <main id="main-content" tabindex="-1" class="shell-main">
      <div ref="errorRegion"><RequestError :error="error" /></div>
      <RouterView />
    </main>
  </div>
</template>
