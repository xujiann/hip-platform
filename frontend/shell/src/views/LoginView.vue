<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">医院信息平台</h2>
      <p class="subtitle">Hospital Information Platform</p>
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
    <!-- 首登/管理员重置后强制改密：不可关闭，改完自动用新密码重新登录再进系统 -->
    <ChangePasswordDialog v-model="forcedDialogVisible" forced @success="onForcedChanged" />
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const forcedDialogVisible = ref(false)
const form = reactive({ username: '', password: '' })

async function onLogin() {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    if (auth.mustChangePassword) {
      // 服务端兜底会对业务接口一律回 1009，进去也办不了事——先改密再放行
      forcedDialogVisible.value = true
      return
    }
    router.push('/')
  } finally {
    loading.value = false
  }
}

async function onForcedChanged(newPassword: string) {
  // 改密瞬间旧 token 已失效（口令戳不匹配），用新密码自动重登再进系统
  try {
    await auth.login(form.username, newPassword)
    ElMessage.success('密码修改成功')
    router.push('/')
  } catch {
    // 自动重登失败（极少见）：留在登录页手工登录即可
    auth.logout()
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a6fc9 0%, #26a69a 100%);
}
.login-card {
  width: 380px;
  padding: 12px 8px;
}
.title {
  text-align: center;
  margin: 8px 0 4px;
  color: #1a6fc9;
}
.subtitle {
  text-align: center;
  color: #999;
  font-size: 12px;
  margin: 0 0 24px;
}
.login-btn {
  width: 100%;
}
</style>
