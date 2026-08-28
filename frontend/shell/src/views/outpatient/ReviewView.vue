<template>
  <el-card>
    <div class="toolbar">
      <h3>审方工作台（在途处方）</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="groupNo" label="处方号" width="150" />
      <el-table-column prop="patientName" label="患者" width="90" />
      <el-table-column label="过敏史" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.allergyHistory" type="danger" size="small">{{ row.allergyHistory }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="itemName" label="药品" />
      <el-table-column prop="spec" label="规格" width="130" />
      <el-table-column prop="usage" label="用法" width="160" />
      <el-table-column prop="qty" label="数量" width="60" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="success" :loading="approveBusy === row.orderId" @click="approve(row)">通过</el-button>
          <el-button link type="danger" :loading="rejectBusy === row.orderId" @click="reject(row)">拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">拒绝将作废该处方行（未收费前）；审方为非强制模式，未审处方仍可收费。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const approveBusy = ref<unknown>(null)
const rejectBusy = ref<unknown>(null)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/outpatient/review/pending')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function approve(row: Record<string, unknown>) {
  approveBusy.value = row.orderId
  try {
    await client.put(`/outpatient/review/${row.orderId}/approve`)
    ElMessage.success('已通过')
    await load()
  } finally {
    approveBusy.value = null
  }
}

async function reject(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('请输入拒绝原因', '拒绝处方', { inputValue: '用法用量不适宜' }).catch(() => null)
  if (!res) return
  const { value } = res
  rejectBusy.value = row.orderId
  try {
    await client.put(`/outpatient/review/${row.orderId}/reject`, null, { params: { reason: value } })
    ElMessage.success('已拒绝并作废')
    await load()
  } finally {
    rejectBusy.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.hint { color: #999; font-size: 12px; margin-top: 12px; }
</style>
