<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import HeaderControls from '../../components/HeaderControls.vue'
import RequestError from '../../components/RequestError.vue'
import { HttpFailure } from '../../lib/http/client'
import { loginDestination } from '../../router'
import { useSession } from './session'

const session = useSession()
const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const visible = ref(false)
const submitted = ref(false)
const error = ref<unknown>(null)
const form = ref<HTMLFormElement | null>(null)
const heading = ref<HTMLElement | null>(null)
const usernameError = computed(() => submitted.value && !username.value ? '请输入用户名' : '')
const passwordError = computed(() => submitted.value && !password.value ? '请输入密码' : '')
const errorMessage = computed(() => error.value instanceof HttpFailure && error.value.status === 401 ? '用户名或密码不正确' : '')

/** 仅校验可本地判断的必填项；密码不修剪、不持久化。 */
async function submit() {
  if (session.busy) return
  submitted.value = true
  error.value = null
  if (usernameError.value || passwordError.value) {
    await nextTick()
    form.value?.querySelector<HTMLInputElement>('[aria-invalid="true"]')?.focus()
    return
  }
  try {
    await session.login(username.value, password.value)
    password.value = ''
    await router.replace(loginDestination(route.query.redirect))
  } catch (failure) {
    error.value = failure
    password.value = ''
    await nextTick()
    form.value?.querySelector<HTMLElement>('.request-error')?.focus()
  }
}
onMounted(() => heading.value?.focus())
</script>

<template>
  <div class="login-page">
    <header class="login-tools"><span class="mobile-brand">Ordivane</span><HeaderControls /></header>
    <main id="main-content" class="login-main">
      <p class="login-brand">Ordivane</p>
      <h1 ref="heading" tabindex="-1">登录</h1>
      <p v-if="session.notice" class="session-notice" role="status">{{ session.notice }}</p>
      <form ref="form" novalidate :aria-busy="session.busy" @submit.prevent="submit">
        <div class="form-field">
          <label for="username">用户名</label>
          <input id="username" v-model="username" name="username" autocomplete="username" required
            :readonly="session.busy" :aria-invalid="Boolean(usernameError)" :aria-describedby="usernameError ? 'username-error' : undefined" />
          <p v-if="usernameError" id="username-error" class="field-error">{{ usernameError }}</p>
        </div>
        <div class="form-field">
          <label for="password">密码</label>
          <div class="password-control">
            <input id="password" v-model="password" name="password" :type="visible ? 'text' : 'password'" autocomplete="current-password" required
              :readonly="session.busy" :aria-invalid="Boolean(passwordError)" :aria-describedby="passwordError ? 'password-error' : undefined" />
            <button type="button" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button>
          </div>
          <p v-if="passwordError" id="password-error" class="field-error">{{ passwordError }}</p>
        </div>
        <RequestError :error="error" :message="errorMessage" />
        <button class="primary-button login-submit" type="submit" :disabled="session.busy">{{ session.busy ? '登录中…' : '登录' }}</button>
      </form>
    </main>
  </div>
</template>
