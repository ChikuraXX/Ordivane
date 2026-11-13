import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import './styles/main.css'

// 身份仅保存在当前内存；主题在首屏挂载前由 index.html 应用。
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
