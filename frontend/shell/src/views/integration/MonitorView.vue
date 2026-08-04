<template>
  <div>
    <el-card style="margin-bottom: 12px">
      <template #header>
        危急值告警
        <el-button link type="primary" style="float: right" @click="loadAlerts">刷新</el-button>
      </template>
      <el-table :data="alerts" size="small" v-loading="loadingAlerts">
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="content" label="内容" />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ String(row.createdAt).slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'NEW' ? 'danger' : 'success'" size="small">
              {{ row.status === 'NEW' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'NEW'" link type="primary" @click="handleAlert(row)">确认处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        集成消息日志
        <div style="float: right; display: flex; gap: 8px">
          <el-select v-model="channel" clearable placeholder="全部通道" style="width: 140px" @change="loadLogs">
            <el-option label="HL7 检验" value="HL7_LIS" />
            <el-option label="医保" value="YB" />
          </el-select>
          <el-button link type="primary" @click="loadLogs">刷新</el-button>
        </div>
      </template>
      <el-table :data="logs" size="small" v-loading="loadingLogs">
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'IN' ? 'primary' : 'warning'" size="small">
              {{ row.direction === 'IN' ? '进站' : '出站' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="channel" label="通道" width="90" />
        <el-table-column prop="refNo" label="关联单号" width="170" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OK' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="error" label="错误" width="180" show-overflow-tooltip />
        <el-table-column label="报文" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="payload">{{ row.payload }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ String(row.createdAt).slice(0, 19).replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const alerts = ref<Record<string, unknown>[]>([])
const logs = ref<Record<string, unknown>[]>([])
const channel = ref<string | null>(null)
const loadingAlerts = ref(false)
const loadingLogs = ref(false)

async function loadAlerts() {
  loadingAlerts.value = true
  try {
    const resp = await client.get('/outpatient/critical-alerts', { params: { status: 'ALL' } })
    alerts.value = resp.data.data
  } finally {
    loadingAlerts.value = false
  }
}

async function handleAlert(row: Record<string, unknown>) {
  await client.put(`/outpatient/critical-alerts/${row.id}/handle`)
  ElMessage.success('已确认处理')
  await loadAlerts()
}

async function loadLogs() {
  loadingLogs.value = true
  try {
    const resp = await client.get('/integration/logs', { params: { channel: channel.value || undefined } })
    logs.value = resp.data.data
  } finally {
    loadingLogs.value = false
  }
}

onMounted(() => {
  loadAlerts()
  loadLogs()
})
</script>

<style scoped>
.payload { font-family: monospace; font-size: 11px; color: #666; }
</style>
