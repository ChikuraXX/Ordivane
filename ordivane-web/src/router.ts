import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './features/auth/session'

/** 仅注册已交付的登录、案件与准确版本计划页面。 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./features/auth/LoginView.vue') },
    { path: '/', component: () => import('./layouts/AppShell.vue'), children: [
      { path: '', redirect: '/incidents' },
      { path: 'incidents', name: 'incidents', component: () => import('./features/incidents/IncidentList.vue') },
      { path: 'incidents/:incidentId([0-9a-fA-F-]{36})', name: 'incident', component: () => import('./features/incidents/IncidentDetail.vue') },
      { path: 'incidents/:incidentId([0-9a-fA-F-]{36})/executions/:executionId([0-9a-fA-F-]{36})', name: 'execution', component: () => import('./features/executions/ExecutionDetail.vue') },
      { path: 'incidents/:incidentId([0-9a-fA-F-]{36})/plans/:planId(new|[0-9a-fA-F-]{36})', name: 'recovery-plan', component: () => import('./features/recovery-plans/RecoveryPlan.vue') },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior(to, from, saved) { return saved ?? (to.path === from.path ? undefined : { top: 0 }) },
})

/** 仅允许已交付的站内目的地，拒绝外部地址和未实现页面。 */
export function loginDestination(value: unknown): string {
  return typeof value === 'string' && !/[\\\r\n]/.test(value)
    && /^\/incidents(?:\/[0-9a-fA-F-]{36}(?:\/plans\/(?:new|[0-9a-fA-F-]{36})|\/executions\/[0-9a-fA-F-]{36})?)?(?:\?[^#]*)?(?:#[A-Za-z0-9_-]+)?$/.test(value) ? value : '/incidents'
}

router.beforeEach(async to => {
  const session = useSession()
  await session.restore()
  if (session.recoveryError) return true
  if (!session.authenticated && to.name !== 'login') return { name: 'login', query: { redirect: to.fullPath } }
  if (session.authenticated && to.name === 'login') return loginDestination(to.query.redirect)
  return true
})
