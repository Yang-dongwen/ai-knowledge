import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import './assets/styles/global.scss'
import { useThemeStore } from './stores/theme.store'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(Antd)

// 尽早应用主题，减少路由首屏闪烁
useThemeStore(pinia).init()

app.mount('#app')
