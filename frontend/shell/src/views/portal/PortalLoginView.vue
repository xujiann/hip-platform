<template>
  <div class="portal-page">
    <div class="portal-card">
      <h2>掌上医院</h2>
      <p class="sub">{{ hospitalName }} · 患者服务</p>
      <el-form @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="patientNo" placeholder="患者号（如 P00000002）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="phone" placeholder="建档手机号" size="large" />
        </el-form-item>
        <el-button type="success" size="large" class="btn" :loading="loading" @click="onLogin">登 录</el-button>
      </el-form>
      <p class="hint">MVP 演示以患者号+手机号登录；正式版将接入电子健康卡与微信实名。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { portalClient } from '../../api/client'

const router = useRouter()
const patientNo = ref('')
const phone = ref('')
const loading = ref(false)
const hospitalName = ref('')

onMounted(async () => {
  // 失败不留未处理 rejection：院名取不到就静默用默认标题（弱网/后端未启时登录页仍可用）
  try {
    const resp = await portalClient.get('/config/public', { timeout: 8000, baseURL: '/api' })
    hospitalName.value = resp.data.data.hospital_name ?? ''
  } catch {
    hospitalName.value = ''
  }
})

async function onLogin() {
  if (!patientNo.value || !phone.value) return
  loading.value = true
  try {
    // portalClient（1.2.3）：登录接口也走统一客户端——在途去重挡住 loading 渲染前的极速双击
    // （双击=两次失败计数，弱网下更易触发锁定）
    const resp = await portalClient.post('/login',
        { patientNo: patientNo.value, phone: phone.value })
    localStorage.setItem('hip_portal_token', resp.data.data.token)
    localStorage.setItem('hip_portal_name', resp.data.data.patientName)
    router.push('/portal/home')
  } catch {
    // 统一拦截器已按失败类型提示（业务码/网络/去重各有其文案），此处再弹即双提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.portal-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #1f9d6d 0%, #16786f 100%);
}
.portal-card {
  width: min(92vw, 380px);
  background: #fff;
  border-radius: 12px;
  padding: 28px 24px;
}
h2 { text-align: center; margin: 0 0 4px; color: #16786f; }
.sub { text-align: center; color: #999; font-size: 13px; margin: 0 0 20px; }
.btn { width: 100%; }
.hint { color: #bbb; font-size: 11px; margin-top: 14px; text-align: center; }
</style>
