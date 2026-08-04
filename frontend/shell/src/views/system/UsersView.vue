<template>
  <el-card>
    <div class="toolbar">
      <h3>用户管理</h3>
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="title" label="岗位" width="100" />
      <el-table-column label="科室" width="140">
        <template #default="{ row }">{{ deptName(row.deptId) }}</template>
      </el-table-column>
      <el-table-column label="角色">
        <template #default="{ row }">
          <el-tag v-for="c in row.roleCodes" :key="c" size="small" style="margin-right: 4px">
            {{ roleName(c) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.username !== 'admin'" link :type="row.enabled ? 'danger' : 'success'"
                     @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="pageNo" :page-size="pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 12px"
                   @current-change="load" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item :label="form.id ? '重置密码' : '初始密码'" :required="!form.id">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="form.id ? '留空则不修改' : '至少 6 位'" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="岗位">
          <el-select v-model="form.title" clearable style="width: 100%">
            <el-option v-for="t in ['医师', '护士', '药师', '技师', '收费员', '管理员']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="form.deptId" clearable style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface UserRow {
  id: number | null
  username: string
  realName: string
  title: string | null
  deptId: number | null
  phone: string | null
  enabled: boolean
  roleCodes: string[]
}

const records = ref<UserRow[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 20
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const depts = ref<{ id: number; name: string }[]>([])
const roles = ref<{ code: string; name: string }[]>([])

const form = reactive<UserRow & { password: string }>({
  id: null, username: '', password: '', realName: '', title: null,
  deptId: null, phone: null, enabled: true, roleCodes: [],
})

function deptName(id: number | null) {
  return depts.value.find((d) => d.id === id)?.name ?? ''
}
function roleName(code: string) {
  return roles.value.find((r) => r.code === code)?.name ?? code
}

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/system/users', { params: { page: pageNo.value - 1, size: pageSize } })
    records.value = resp.data.data.records
    total.value = resp.data.data.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: null, username: '', password: '', realName: '', title: null, deptId: null, phone: null, roleCodes: [] })
  dialogVisible.value = true
}

function openEdit(row: UserRow) {
  Object.assign(form, { ...row, password: '' })
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await client.put(`/system/users/${form.id}`, form)
    } else {
      await client.post('/system/users', form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: UserRow) {
  await client.put(`/system/users/${row.id}/enabled`, null, { params: { enabled: !row.enabled } })
  await load()
}

onMounted(async () => {
  const [d, r] = await Promise.all([client.get('/system/depts'), client.get('/system/roles')])
  depts.value = d.data.data
  roles.value = r.data.data
  await load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar h3 { margin: 0; }
</style>
