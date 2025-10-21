import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Tailwind v4 (仅theme+utilities，无preflight，避免与Element冲突)
import './styles/tailwind.css'
import App from './App.vue'
import router from './router'
import './styles/admin.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
