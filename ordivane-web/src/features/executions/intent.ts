/** 非秘密执行意图；身份、准确计划和请求体不可变。 */
export interface IntentBinding { identityId: string; planId: string; version: number; digest: string }
/** 执行ID取得前保留原键，取得后只读回查。 */
export interface ExecutionIntent extends IntentBinding { key: string; executionId: string | null }
/** localStorage只保存必要标识；独立标记检测单项丢失，内存亦不允许悄悄换键。 */
export class IntentStore {
  private readonly seen = new Set<string>()
  constructor(private readonly storage: Pick<Storage, 'getItem' | 'setItem'>) {}
  /** 包含身份和不可变版本，切换对象不覆盖旧意图。 */
  keyFor(b: IntentBinding): string { return `ordivane.execution.v1:${b.identityId}:${b.planId}:${b.version}:${b.digest}` }
  /** 读取失败或残缺必须留给人工查证，不能回退为新请求。 */
  read(b: IntentBinding): ExecutionIntent | null {
    try {
      const key = this.keyFor(b), raw = this.storage.getItem(key), marker = this.storage.getItem(`${key}:confirmed`)
      if (!raw) { if (marker || this.seen.has(key)) throw Error(); return null }
      const v: unknown = JSON.parse(raw)
      if (typeof v !== 'object' || v === null || !('key' in v) || typeof v.key !== 'string'
        || !/^[a-zA-Z0-9._:-]{1,128}$/.test(v.key) || !('executionId' in v)
        || !(v.executionId === null || typeof v.executionId === 'string')) throw Error()
      for (const field of ['identityId', 'planId', 'version', 'digest'] as const) {
        if (!(field in v) || Reflect.get(v, field) !== b[field]) throw Error()
      }
      this.seen.add(key)
      return { ...b, key: v.key, executionId: v.executionId }
    } catch { throw new Error('执行结果未确认：原意图存储不可用或已丢失，请保留现有信息查证，不能换键发送。') }
  }
  /** 只在用户确认时创建；先持久化标记和原体再允许网络发送。 */
  confirm(b: IntentBinding): ExecutionIntent {
    const prior = this.read(b)
    if (prior) return prior
    const intent: ExecutionIntent = { ...b, key: crypto.randomUUID(), executionId: null }
    this.save(intent)
    return intent
  }
  /** 写入失败保留不确定标记，禁止自动换键。 */
  save(intent: ExecutionIntent): void {
    const key = this.keyFor(intent)
    this.seen.add(key)
    try {
      this.storage.setItem(`${key}:confirmed`, '1')
      this.storage.setItem(key, JSON.stringify(intent))
      if (this.storage.getItem(key) !== JSON.stringify(intent)) throw Error()
    } catch { throw new Error('执行结果未确认：无法保存原幂等意图，请先查证。') }
  }
}
