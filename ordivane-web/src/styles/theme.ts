import { ref } from 'vue'

/** 可选主题；浏览器只允许持久化此项偏好。 */
export type Theme = 'light' | 'dark'
export const theme = ref<Theme>(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')
let explicit = false
try { explicit = ['light', 'dark'].includes(localStorage.getItem('ordivane.theme') ?? '') } catch { /* 存储不可用时使用内存。 */ }

/** 应用选择并尽力持久化；不刷新页面或业务请求。 */
export function chooseTheme(value: Theme) {
  explicit = true
  applyTheme(value)
  try { localStorage.setItem('ordivane.theme', value) } catch { /* 本次会话内仍生效。 */ }
}
/** 同步文档颜色方案，含浏览器原生控件。 */
function applyTheme(value: Theme) {
  theme.value = value
  document.documentElement.dataset.theme = value
  document.documentElement.classList.toggle('dark', value === 'dark')
  document.documentElement.style.colorScheme = value
}
/** 未显式选择时跟随系统；返回清理函数避免遗留监听。 */
export function observeSystemTheme(): () => void {
  const media = matchMedia('(prefers-color-scheme: dark)')
  const update = () => { if (!explicit) applyTheme(media.matches ? 'dark' : 'light') }
  media.addEventListener('change', update)
  update()
  return () => media.removeEventListener('change', update)
}
