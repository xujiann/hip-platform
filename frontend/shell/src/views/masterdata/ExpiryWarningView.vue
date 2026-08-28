<template>
  <el-card>
    <div class="toolbar">
      <h3>近效期预警</h3>
      <div class="ops">
        <span>预警天数</span>
        <el-input-number v-model="days" :min="1" :max="3650" size="small" @change="load" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button size="small" type="primary" :loading="scanning" @click="scan">手动巡检开单</el-button>
      </div>
    </div>
    <p class="hint">
      近效期在库量为<b>估算口径</b>：按 FEFO（先到期先出）将发药净出量分摊到各入库批次，
      入库量减去分摊消耗即估算在库量，供药师人工复核，不作精确批次结论。
    </p>
    <el-table :data="records" v-loading="loading" border stripe empty-text="无近效期批次">
      <el-table-column prop="drugName" label="药品" />
      <el-table-column prop="batchNo" label="批号" width="130">
        <template #default="{ row }">{{ row.batchNo || '—' }}</template>
      </el-table-column>
      <el-table-column prop="expireDate" label="效期" width="120" />
      <el-table-column label="剩余天数" width="100">
        <template #default="{ row }">
          <span :class="{ danger: row.daysToExpire < 0 }">{{ row.daysToExpire }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="batchQty" label="入库量" width="90" />
      <el-table-column prop="estimatedRemaining" label="估算在库" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'EXPIRED' ? 'danger' : 'warning'" size="small">
            {{ row.status === 'EXPIRED' ? '已过期' : '近效期' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const days = ref(90)
const loading = ref(false)
const scanning = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/inventory/expiry-warning', { params: { days: days.value } })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function scan() {
  scanning.value = true
  try {
    const resp = await client.post('/inventory/expiry-scan')
    ElMessage.success(`巡检完成，本轮新开提醒 ${resp.data.data.opened} 条（同题不重复开单）`)
  } finally {
    scanning.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.toolbar h3 { margin: 0; }
.ops { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.hint { color: #999; font-size: 12px; margin: 0 0 12px; }
.danger { color: #f56c6c; font-weight: 600; }
</style>
