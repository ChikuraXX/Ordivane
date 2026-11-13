/** 从已经验证为JSON的响应中按路径保留原始片段，避免大整数被展示层舍入。 */
export function rawAt(source: string, path: readonly (string | number)[]): string {
  let offset = 0
  const skip = () => { while (/\s/.test(source[offset] ?? '') && offset < source.length) offset++ }
  function stringEnd(): number {
    offset++
    while (offset < source.length) { const c = source[offset++]; if(c === '\\') offset++; else if(c === '"') break }
    return offset
  }
  function value(depth: number): string | undefined {
    skip(); const start = offset, c = source[offset]
    if (c === '{' || c === '[') {
      const object = c === '{', end = object ? '}' : ']'; offset++; skip(); let index = 0, found: string | undefined
      while (source[offset] !== end && offset < source.length) {
        skip(); let key: string | number = index++
        if(object) { const keyStart = offset; stringEnd(); key = JSON.parse(source.slice(keyStart,offset)); skip(); offset++ }
        const child = value(depth >= 0 && path[depth] === key ? depth+1 : -1)
        if(child !== undefined) found = child
        skip(); if(source[offset] === ',') offset++; else break
      }
      offset++
      return depth === path.length ? source.slice(start,offset) : found
    }
    if(c === '"') stringEnd()
    else while(offset < source.length && !/[\s,}\]]/.test(source[offset] ?? '')) offset++
    return depth === path.length ? source.slice(start,offset) : undefined
  }
  const result = value(0)
  if(result === undefined) throw new Error('原始观测片段缺失')
  return result
}
