import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // 技术债三：element-plus 按需引入（模板组件与 v-loading 指令自动解析并带样式）
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  build: {
    rollupOptions: {
      output: {
        // 三十七期：主包分包。技术债三改造：element-plus 不能手动归并单 chunk——
        // 其 es/index.mjs 是 export * 重导出枢纽，跨 chunk 重导出会令 rollup 保留全部
        // 组件导出（实测 91 vs 49 个组件），交给自动分 chunk 才能按需摇树
        manualChunks(id) {
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) {
            return undefined
          }
          if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) {
            return 'echarts'
          }
          if (id.includes('node_modules')) {
            return 'vendor'
          }
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
