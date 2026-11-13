<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { roleNames, useSession } from '../features/auth/session'
import { chooseTheme, theme } from '../styles/theme'
import type { Theme } from '../styles/theme'

/** 登录与应用壳共享控件；两个瞬时菜单互斥。 */
defineProps<{ account?: boolean }>()
const emit = defineEmits<{ logout: [] }>()
const session = useSession()
const route = useRoute()
const root = ref<HTMLElement | null>(null)
const themeButton = ref<HTMLButtonElement | null>(null)
const accountButton = ref<HTMLButtonElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const open = ref<'theme' | 'account' | null>(null)
const role = computed(() => session.identity ? roleNames[session.identity.role] : '')

/** 收尾时将焦点交还仍存在的触发器。 */
function close(restore = true) {
  const trigger = open.value === 'theme' ? themeButton.value : accountButton.value
  open.value = null
  if (restore) trigger?.focus()
}
/** 打开后焦点立即进入可交互项，不等待动画。 */
async function toggle(menu: 'theme' | 'account') {
  if (open.value === menu) { close(); return }
  open.value = menu
  await nextTick()
  panel.value?.querySelector<HTMLButtonElement>('[aria-checked="true"], button')?.focus()
}
/** 菜单方向键循环移动，Escape关闭，Tab按自然顺序离开。 */
function keys(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key === 'Tab') {
    event.preventDefault()
    const trigger = open.value === 'theme' ? themeButton.value : accountButton.value
    const items = Array.from(document.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]'))
      .filter(item => item.getBoundingClientRect().width > 0 && !panel.value?.contains(item))
    const index = items.findIndex(item => item === trigger)
    close(false)
    items[(index + (event.shiftKey ? -1 : 1) + items.length) % items.length]?.focus()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const items = Array.from(panel.value?.querySelectorAll<HTMLButtonElement>('button') ?? [])
  const index = items.findIndex(item => item === document.activeElement)
  const target = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
  items[target]?.focus()
}
/** 主题切换保留草稿，选择后归还焦点。 */
function select(value: Theme) { chooseTheme(value); close() }
/** 退出仅发送意图，实际请求和失败反馈由应用壳负责。 */
function logout() { close(); emit('logout') }
/** 点外关闭不抢走用户正在点击的其他控件焦点。 */
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) close(false)
}
/** 断点/可用空间变化时关闭浮层，避免离屏。 */
function resize() { if (open.value) close() }
/** 离场动画中的旧项立即移出键盘及辅助技术交互范围。 */
function conceal(element: Element) {
  element.setAttribute('inert', '')
  element.setAttribute('aria-hidden', 'true')
}
watch(() => route.fullPath, () => close(false))
onMounted(() => {
  document.addEventListener('pointerdown', outside)
  window.addEventListener('resize', resize)
  window.visualViewport?.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', outside)
  window.removeEventListener('resize', resize)
  window.visualViewport?.removeEventListener('resize', resize)
})
</script>

<template>
  <div ref="root" class="header-controls">
    <div class="theme-desktop" role="group" aria-label="主题">
      <button type="button" :aria-pressed="theme === 'light'" @click="chooseTheme('light')">浅色</button>
      <button type="button" :aria-pressed="theme === 'dark'" @click="chooseTheme('dark')">深色</button>
    </div>
    <button ref="themeButton" class="theme-mobile quiet-button" type="button" aria-haspopup="menu"
      :aria-expanded="open === 'theme'" aria-controls="theme-menu" @click="toggle('theme')" @keydown.down.prevent="toggle('theme')">主题</button>
    <button v-if="account" ref="accountButton" class="account-button quiet-button" type="button" aria-haspopup="menu"
      :aria-expanded="open === 'account'" aria-controls="account-menu" :aria-label="`账户：${session.identity?.username}，${role}`"
      :disabled="session.busy" @click="toggle('account')" @keydown.down.prevent="toggle('account')">
      <span class="account-desktop">{{ session.identity?.username }} · {{ role }}</span><span class="account-mobile">账户</span>
      <span aria-hidden="true" class="chevron">⌄</span>
    </button>
    <Transition name="menu" @before-leave="conceal">
      <div v-if="open" :id="open === 'theme' ? 'theme-menu' : 'account-menu'" ref="panel" class="control-menu" :class="{ 'theme-menu': open === 'theme' }"
        role="menu" :aria-label="open === 'theme' ? '主题' : '账户'" @keydown="keys">
        <template v-if="open === 'theme'">
          <button type="button" role="menuitemradio" :aria-checked="theme === 'light'" @click="select('light')">浅色 <span v-if="theme === 'light'" aria-hidden="true">✓</span></button>
          <button type="button" role="menuitemradio" :aria-checked="theme === 'dark'" @click="select('dark')">深色 <span v-if="theme === 'dark'" aria-hidden="true">✓</span></button>
        </template>
        <template v-else>
          <div class="menu-identity" role="presentation"><p>{{ session.identity?.username }}</p><p class="secondary">{{ role }}</p></div>
          <button type="button" role="menuitem" @click="logout">退出</button>
        </template>
      </div>
    </Transition>
  </div>
</template>
