<template>
  <el-card>
    <div class="toolbar">
      <h3>入库验收</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
    </div>
    <p class="hint">新入库先进入待验收，验收通过后才真正加库存并记流水；不合格可拒收（不入库）。</p>
    <el-table :data="records" v-loading="loading" border stripe empty-text="无待验收入库单">
      <el-table-column prop="inNo" label="入库单号" width="170" />
      <el-table-column prop="drugName" label="药品" />
      <el-table-column prop="qty" label="数量" width="80" />
      <el-table-column prop="batchNo" label="批号" width="120" />
      <el-table-column prop="expireDate" label="效期" width="120" />
      <el-table-column prop="supplier" label="供应商" width="140" />
      <el-table-column prop="purchaseNo" label="采购单号" width="130">
        <template #default="{ row }">{{ row.purchaseNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button type="success" size="small" :loading="busyId === row.id" @click="accept(row)">通过</el-button>
          <el-button type="danger" size="small" :loading="busyId === row.id" @click="reject(row)">拒收</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const busyId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/inventory/stock-ins/pending')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function accept(row: Record<string, unknown>) {
  busyId.value = row.id as number
  try {
    await client.post(`/inventory/stock-in/${row.id}/accept`)
    ElMessage.success('验收通过，已入库')
    await load()
  } finally {
    busyId.value = null
  }
}

async function reject(row: Record<string, unknown>) {
  // 拒收原因必填：后端 8012 亦兜底，前端先收集给出更好体验
  const { value } = await ElMessageBox.prompt('请填写拒收原因', '拒收入库单', {
    confirmButtonText: '确认拒收',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim() ? true : '拒收原因必填'),
  }).catch(() => ({ value: null }))
  if (!value) return
  busyId.value = row.id as number
  try {
    await client.post(`/inventory/stock-in/${row.id}/reject`, { reason: value })
    ElMessage.success('已拒收')
    await load()
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar h3 { margin: 0; }
.hint { color: #999; font-size: 12px; margin: 0 0 12px; }
</style>
