import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // element-plus 按需引入（同 shell：不可手动归并单 chunk，交给自动分包摇树）
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) {
            return undefined
          }
          if (id.includes('node_modules')) {
            return 'vendor'
          }
        },
      },
    },
  },
  server: {
    // autoPort：预览环境经 PORT 注入端口，本地手工启动回落 5174
    port: Number(process.env.PORT) || 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
})
