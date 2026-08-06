<template>
  <el-card>
    <div class="toolbar">
      <h3>科室管理</h3>
      <el-button type="primary" @click="openCreate">新增科室</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="科室名称" width="180" />
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ typeNames[row.type] ?? row.type }}</template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
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
          <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑科室' : '新增科室'" width="420px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码" required><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="(label, value) in typeNames" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortNo" :min="0" /></el-form-item>
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

interface DeptRow {
  id: number | null
  name: string
  code: string
  type: string
  sortNo: number
  enabled: boolean
}

const typeNames: Record<string, string> = {
  CLINICAL: '临床科室',
  MEDTECH: '医技科室',
  NURSING: '护理单元',
  ADMIN: '行政后勤',
}

const records = ref<DeptRow[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const form = reactive<DeptRow>({ id: null, name: '', code: '', type: 'CLINICAL', sortNo: 0, enabled: true })

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/system/depts')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: null, name: '', code: '', type: 'CLINICAL', sortNo: 0 })
  dialogVisible.value = true
}

function openEdit(row: DeptRow) {
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await client.put(`/system/depts/${form.id}`, form)
    } else {
      await client.post('/system/depts', form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: DeptRow) {
  await client.put(`/system/depts/${row.id}/enabled`, null, { params: { enabled: !row.enabled } })
  await load()
}

onMounted(load)
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
