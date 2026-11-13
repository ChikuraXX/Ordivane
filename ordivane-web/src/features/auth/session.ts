import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearCsrf, HttpFailure, isRecord, refreshCsrf, request, sessionWrite } from '../../lib/http/client'

/** 三个互不继承的人类角色；权威值来自服务端 me。 */
export type AccessRole = 'OBSERVER' | 'OPERATOR' | 'APPROVER'
/** 当前已验证身份，不包含密码或来源凭据。 */
export interface Identity { id: string; username: string; role: AccessRole }
/** 角色展示名称不参与后端授权。 */
export const roleNames: Record<AccessRole, string> = { OBSERVER: '观察者', OPERATOR: '操作员', APPROVER: '审批人' }

/** 校验真实身份响应，未知角色不回退为其他权限。 */
function identityFrom(value: unknown): Identity {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id || typeof value.username !== 'string'
    || !value.username || !['OBSERVER', 'OPERATOR', 'APPROVER'].includes(String(value.role))) {
    throw new HttpFailure(0, 'INVALID_RESPONSE', '身份响应无效，请重试')
  }
  const role = value.role
  if (role !== 'OBSERVER' && role !== 'OPERATOR' && role !== 'APPROVER') {
    throw new HttpFailure(0, 'INVALID_RESPONSE', '身份响应无效，请重试')
  }
  return { id: value.id, username: value.username, role }
}

/** 仅共享身份与恢复状态；不持久化身份，不以本地计时判定会话过期。 */
export const useSession = defineStore('session', () => {
  const identity = ref<Identity | null>(null)
  const ready = ref(false)
  const recoveryError = ref<unknown>(null)
  const notice = ref('')
  const busy = ref(false)
  let restoring: Promise<void> | null = null
  const authenticated = computed(() => identity.value !== null)

  /** 401 时立即移除敏感状态；页面跳转由统一认证边界负责。 */
  function invalidate(message = '会话已失效，请重新登录') {
    identity.value = null
    clearCsrf()
    notice.value = message
  }

  /** 首次与前台恢复均查询 me；失败保留可重试边界，不展示旧身份。 */
  async function restore(force = false): Promise<void> {
    if (restoring) return restoring
    if (ready.value && !force) return
    restoring = (async () => {
      recoveryError.value = null
      try {
        identity.value = identityFrom(await request('/api/v1/access/me', {}, false))
        await refreshCsrf()
      } catch (error) {
        const hadIdentity = identity.value !== null
        identity.value = null
        clearCsrf()
        if (error instanceof HttpFailure && error.status === 401) {
          if (hadIdentity) notice.value = '会话已失效，请重新登录'
        } else recoveryError.value = error
      } finally { ready.value = true }
    })()
    try { await restoring } finally { restoring = null }
  }

  /** 表单登录后轮换 CSRF 并获取真实身份；不接受客户端角色。 */
  async function login(username: string, password: string): Promise<void> {
    if (busy.value) return
    busy.value = true
    notice.value = ''
    try {
      identity.value = null
      await refreshCsrf()
      await sessionWrite('/api/v1/access/login', new URLSearchParams({ username, password }), false)
      clearCsrf()
      await refreshCsrf()
      identity.value = identityFrom(await request('/api/v1/access/me', {}, false))
      recoveryError.value = null
    } finally { busy.value = false }
  }

  /** 仅在服务端确认退出或401时清身份；网络/403失败不能伪装成功。 */
  async function logout(): Promise<void> {
    if (busy.value) return
    busy.value = true
    try {
      await sessionWrite('/api/v1/access/logout')
      invalidate('')
    } catch (error) {
      // 过期会话的写请求可能先被CSRF拒绝；仅GET核验，不补发退出。
      if (error instanceof HttpFailure && error.code === 'CSRF_REJECTED') {
        clearCsrf()
        await restore(true)
      }
      throw error
    } finally { busy.value = false }
  }
  return { identity, ready, recoveryError, notice, busy, authenticated, invalidate, restore, login, logout }
})
