import type { HTMLAttributes } from 'vue'

// Element Plus 2.13.0 的 table-v2 声明读取 JSX.IntrinsicAttributes['class']。
// 对齐 Vue 实际支持的 class 输入类型；不放宽为 any，不修改依赖文件。
declare global {
  namespace JSX {
    interface IntrinsicAttributes {
      /** Vue 支持的字符串、数组和对象形式的 class 绑定。 */
      class?: HTMLAttributes['class']
    }
  }
}
