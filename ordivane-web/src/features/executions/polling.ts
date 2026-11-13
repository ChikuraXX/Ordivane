import type { Execution } from './api'
/** 仅计算下一次只读查询间隔；绝不推进领域状态。null表示自动查询已结束。 */
export function pollingDelay(execution: Pick<Execution, 'status' | 'nextCheckAt' | 'verificationDeadline' | 'verificationCount' | 'verificationLimit' | 'actionCompleted' | 'deliveryState'>, now = Date.now()): number | null {
  if (execution.status === 'SUCCEEDED' || execution.status === 'CANCELLED'
    || (execution.actionCompleted && execution.nextCheckAt === null)) return null
  if (execution.status === 'MANUAL_REQUIRED') return null
  if (execution.nextCheckAt !== null) return Math.max(5000, Date.parse(execution.nextCheckAt) - now)
  if (execution.verificationCount >= execution.verificationLimit
    || (execution.verificationDeadline !== null && Date.parse(execution.verificationDeadline) <= now)) return null
  return execution.deliveryState === 'PENDING' || execution.deliveryState === 'STARTED' || execution.status === 'QUEUED' ? 5000 : null
}
