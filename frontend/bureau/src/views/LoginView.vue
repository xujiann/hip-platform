<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">{{ orgName }}</h2>
      <p class="subtitle">医保基金监管案件查办系统</p>
      <el-form :model="form" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password>
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onLogin">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const orgName = ref('医疗保障局')
const form = reactive({ username: '', password: '' })

onMounted(async () => {
  try {
    const resp = await axios.get('/api/config/public')
    if (resp.data?.data?.org_name) orgName.value = resp.data.data.org_name
  } catch { /* 忽略：用默认名 */ }
})

async function onLogin() {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push('/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #16417c 0%, #1d7a5f 100%);
}
.login-card {
  width: 380px;
  padding: 12px 8px;
}
.title {
  text-align: center;
  margin: 8px 0 4px;
  color: #16417c;
}
.subtitle {
  text-align: center;
  color: #999;
  font-size: 13px;
  margin: 0 0 24px;
}
.login-btn {
  width: 100%;
}
</style>
