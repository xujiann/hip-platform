import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 技术债三：element-plus 改按需引入（模板组件由 unplugin-vue-components 自动解析）
// ElMessage/ElMessageBox 为编程式调用，样式须手动引入
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
