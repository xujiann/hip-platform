<template>
  <el-card>
    <div class="toolbar">
      <h3>固定资产台账</h3>
      <el-button type="primary" @click="dialogVisible = true">登记资产</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="assetNo" label="资产编号" width="160" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="category" label="类别" width="110" />
      <el-table-column label="科室" width="120">
        <template #default="{ row }">{{ deptName(row.deptId) }}</template>
      </el-table-column>
      <el-table-column prop="price" label="原值 ¥" width="110" />
      <el-table-column prop="netValue" label="净值 ¥" width="110" />
      <el-table-column prop="purchaseDate" label="购置日期" width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status as string]" size="small">{{ statusNames[row.status as string] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.status === 'IN_USE'" link type="warning" :loading="statusBusyId === row.id" @click="setStatus(row, 'REPAIR')">报修</el-button>
          <el-button v-if="row.status === 'REPAIR'" link type="success" :loading="statusBusyId === row.id" @click="setStatus(row, 'IN_USE')">修复</el-button>
          <el-button v-if="row.status !== 'SCRAPPED'" link type="danger" :loading="statusBusyId === row.id" @click="setStatus(row, 'SCRAPPED')">报废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="登记资产" width="440px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in ['医疗设备', '信息化设备', '办公设备', '房屋建筑']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="form.deptId" clearable style="width: 100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原值 ¥" required><el-input-number v-model="form.price" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="购置日期" required>
          <el-date-picker v-model="form.purchaseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="折旧年限"><el-input-number v-model="form.usefulYears" :min="1" :max="50" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
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

const statusNames: Record<string, string> = { IN_USE: '在用', REPAIR: '维修', SCRAPPED: '报废' }
const statusTag: Record<string, string> = { IN_USE: 'success', REPAIR: 'warning', SCRAPPED: 'info' }

const records = ref<Record<string, unknown>[]>([])
const depts = ref<{ id: number; name: string }[]>([])
const loading = ref(false)
const saving = ref(false)
const statusBusyId = ref<unknown>(null)
const dialogVisible = ref(false)
const form = reactive({
  name: '', category: '医疗设备', deptId: null as number | null,
  price: 10000, purchaseDate: '', usefulYears: 5, remark: '',
})

function deptName(id: unknown) {
  return depts.value.find((d) => d.id === id)?.name ?? ''
}

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/hrp/assets')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await client.post('/hrp/assets', form)
    ElMessage.success('已登记')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function setStatus(row: Record<string, unknown>, status: string) {
  statusBusyId.value = row.id
  try {
    await client.put(`/hrp/assets/${row.id}/status`, null, { params: { status } })
    await load()
  } finally {
    statusBusyId.value = null
  }
}

onMounted(async () => {
  const resp = await client.get('/system/depts')
  depts.value = resp.data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
