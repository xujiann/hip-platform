<template>
  <div class="portal-page">
    <div class="portal-card">
      <h2>掌上医院</h2>
      <p class="sub">峨眉山市人民医院 · 患者服务</p>
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
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const router = useRouter()
const patientNo = ref('')
const phone = ref('')
const loading = ref(false)

async function onLogin() {
  if (!patientNo.value || !phone.value) return
  loading.value = true
  try {
    const resp = await axios.post('/api/portal/login', { patientNo: patientNo.value, phone: phone.value })
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    localStorage.setItem('hip_portal_token', resp.data.data.token)
    localStorage.setItem('hip_portal_name', resp.data.data.patientName)
    router.push('/portal/home')
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
