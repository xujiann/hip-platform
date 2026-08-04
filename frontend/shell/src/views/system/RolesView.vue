<template>
  <el-card>
    <div class="toolbar"><h3>角色权限</h3></div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="角色名称" width="160" />
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column prop="remark" label="说明" />
      <el-table-column prop="menuCount" label="已授权菜单数" width="130" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.code !== 'ADMIN'" link type="primary" @click="openGrant(row)">编辑授权</el-button>
          <el-tag v-else size="small" type="info">全量权限</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`授权：${current?.name}`" width="420px">
      <el-tree ref="treeRef" :data="menuTree" show-checkbox node-key="id" default-expand-all
               :props="{ label: 'name', children: 'children' }" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ElTree } from 'element-plus'
import client from '../../api/client'

interface MenuNode {
  id: number
  parentId: number | null
  name: string
  children?: MenuNode[]
}

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const menuTree = ref<MenuNode[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/system/roles')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function loadMenus() {
  const resp = await client.get('/system/roles/menus')
  const menus: MenuNode[] = resp.data.data
  menuTree.value = menus
    .filter((m) => !m.parentId)
    .map((dir) => ({ ...dir, children: menus.filter((m) => m.parentId === dir.id) }))
}

async function openGrant(row: Record<string, unknown>) {
  current.value = row
  dialogVisible.value = true
  await nextTick()
  // 只回显叶子节点，父节点由半选状态自动呈现
  const leafIds = (row.menuIds as number[]).filter((id) =>
    !menuTree.value.some((dir) => dir.id === id && dir.children?.length))
  treeRef.value?.setCheckedKeys(leafIds)
}

async function save() {
  if (!current.value || !treeRef.value) return
  saving.value = true
  try {
    const ids = [...treeRef.value.getCheckedKeys(), ...treeRef.value.getHalfCheckedKeys()]
    await client.put(`/system/roles/${current.value.id}/menus`, { menuIds: ids })
    ElMessage.success('授权已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load()
  loadMenus()
})
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
