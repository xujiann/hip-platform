<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">案件查办系统</div>
      <el-menu router :default-active="$route.path" background-color="#1c2b45" text-color="#c7d0dc" active-text-color="#409eff">
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
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

onMounted(() => {
  if (!auth.user) auth.fetchMe().catch(() => {})
})

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: #1c2b45; }
.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  background: #142138;
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
