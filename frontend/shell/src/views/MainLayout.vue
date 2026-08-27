<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">医院信息平台</div>
      <el-menu router :default-active="$route.path" background-color="#233044" text-color="#c7d0dc" active-text-color="#409eff">
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon><span>工作台</span>
        </el-menu-item>
        <el-sub-menu v-for="dir in auth.menuTree" :key="dir.id" :index="dir.path || String(dir.id)">
          <template #title>
            <el-icon><component :is="dir.icon || 'Menu'" /></el-icon><span>{{ dir.name }}</span>
          </template>
          <el-menu-item v-for="m in dir.children" :key="m.id" :index="m.path">
            {{ m.name }}
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div />
        <el-dropdown @command="onCommand">
          <span class="user-name">
            {{ auth.user?.realName || auth.user?.username }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
    <ChangePasswordDialog v-model="pwdDialogVisible" :forced="auth.mustChangePassword"
                          @success="onPasswordChanged" />
  </el-container>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'

const router = useRouter()
const auth = useAuthStore()
const pwdDialogVisible = ref(false)

onMounted(() => {
  if (!auth.user) auth.fetchMe().catch(() => {})
})

// 强制改密态下刷新页面：/auth/me 会带回标志，此处补开不可关闭的改密弹窗，
// 否则用户困在"每个接口都提示 1009"的死局里（登录页的弹窗只覆盖登录动线）
watch(() => auth.mustChangePassword, (v) => {
  if (v) pwdDialogVisible.value = true
}, { immediate: true })

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  } else if (cmd === 'changePassword') {
    pwdDialogVisible.value = true
  }
}

function onPasswordChanged() {
  // 改密后口令戳已变，旧 token 服务端即刻失效——主动登出比等下一个请求吃 401 体验好
  ElMessage.success('密码已修改，请重新登录')
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: #233044; }
.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  background: #1b2635;
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
  background: #fff;
}
.user-name { cursor: pointer; color: #333; display: flex; align-items: center; gap: 4px; }
.main { background: #f5f7fa; }
</style>
