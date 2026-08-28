<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="健康概览" name="health">
        <el-descriptions v-if="health" :column="4" border size="small">
          <el-descriptions-item label="数据库">
            <el-tag :type="health.dbUp ? 'success' : 'danger'" size="small">{{ health.dbUp ? '正常' : '异常' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="运行时长">{{ health.uptimeMinutes }} 分钟</el-descriptions-item>
          <el-descriptions-item label="磁盘剩余">{{ health.diskFreeGb }} GB</el-descriptions-item>
          <el-descriptions-item label="堆内存">{{ health.heapUsedMb }} MB</el-descriptions-item>
          <el-descriptions-item label="今日慢接口">{{ health.slowApiToday }} 次</el-descriptions-item>
          <el-descriptions-item label="未结故障">{{ health.openFaults }} 条</el-descriptions-item>
        </el-descriptions>
        <h4>告警</h4>
        <el-alert v-for="(a, i) in (health?.alerts ?? [])" :key="i" :title="a" type="warning" show-icon
                  style="margin-bottom: 6px" :closable="false" />
        <el-alert v-if="health && !health.alerts.length" title="无告警" type="success" show-icon :closable="false" />
      </el-tab-pane>

      <el-tab-pane label="慢接口" name="slow">
        <el-table :data="slowApis" size="small" border>
          <el-table-column prop="method" label="方法" width="80" />
          <el-table-column prop="path" label="接口" show-overflow-tooltip />
          <el-table-column prop="times" label="7日次数" width="90" />
          <el-table-column prop="avg_ms" label="平均耗时(ms)" width="110" />
          <el-table-column prop="max_ms" label="最大耗时(ms)" width="110" />
          <el-table-column prop="last_at" label="最近发生" width="180" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="故障台账" name="faults">
        <el-form inline size="small">
          <el-form-item><el-input v-model="fault.title" placeholder="故障描述" style="width: 280px" /></el-form-item>
          <el-form-item>
            <el-select v-model="fault.level" style="width: 110px">
              <el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" :loading="createFaultLoading" @click="createFault">登记故障</el-button>
        </el-form>
        <el-table :data="faults" size="small" border>
          <el-table-column prop="id" label="#" width="60" />
          <el-table-column prop="title" label="故障" show-overflow-tooltip />
          <el-table-column label="级别" width="80">
            <template #default="{ row }">
              <el-tag :type="row.level === 'HIGH' ? 'danger' : row.level === 'MEDIUM' ? 'warning' : 'info'" size="small">
                {{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reporter" label="登记人" width="100" />
          <el-table-column prop="created_at" label="登记时间" width="170" />
          <el-table-column label="处理" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'OPEN'" link type="success" size="small"
                         :loading="busyId === row.id" @click="resolveFault(row)">
                标记解决</el-button>
              <span v-else>{{ row.resolve_note }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="巡检台账" name="inspections">
        <el-form inline size="small">
          <el-form-item><el-input v-model="insp.item" placeholder="巡检项（如：备份文件校验）" style="width: 260px" /></el-form-item>
          <el-form-item>
            <el-select v-model="insp.result" style="width: 110px">
              <el-option label="通过" value="PASS" /><el-option label="异常" value="FAIL" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="insp.note" placeholder="备注" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" :loading="addInspectionLoading" @click="addInspection">登记巡检</el-button>
        </el-form>
        <el-table :data="inspections" size="small" border>
          <el-table-column prop="item" label="巡检项" show-overflow-tooltip />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.result === 'PASS' ? 'success' : 'danger'" size="small">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" show-overflow-tooltip />
          <el-table-column prop="inspector" label="巡检人" width="100" />
          <el-table-column prop="checked_at" label="时间" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="备份记录" name="backups">
        <el-alert title="备份由 tools/db-backup.ps1 定时执行（任务计划程序），恢复演练用 tools/db-restore.ps1"
                  type="info" show-icon :closable="false" style="margin-bottom: 8px" />
        <el-table :data="backups" size="small" border>
          <el-table-column prop="file_name" label="文件" show-overflow-tooltip />
          <el-table-column label="大小" width="110">
            <template #default="{ row }">{{ Math.round(Number(row.size_bytes) / 1024) }} KB</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'FAILED' ? 'danger' : 'success'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" show-overflow-tooltip />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

interface Health {
  dbUp: boolean
  uptimeMinutes: number
  diskFreeGb: number
  heapUsedMb: number
  slowApiToday: number
  openFaults: number
  alerts: string[]
}

const tab = ref('health')
const health = ref<Health | null>(null)
const slowApis = ref<Record<string, unknown>[]>([])
const faults = ref<Record<string, unknown>[]>([])
const inspections = ref<Record<string, unknown>[]>([])
const backups = ref<Record<string, unknown>[]>([])
const fault = reactive({ title: '', level: 'LOW' })
const insp = reactive({ item: '', result: 'PASS', note: '' })
const createFaultLoading = ref(false)
const addInspectionLoading = ref(false)
const busyId = ref<unknown>(null)

async function loadHealth() { health.value = (await client.get('/ops/health-overview')).data.data }
async function loadSlow() { slowApis.value = (await client.get('/ops/slow-apis')).data.data }
async function loadFaults() { faults.value = (await client.get('/ops/faults')).data.data }
async function loadInspections() { inspections.value = (await client.get('/ops/inspections')).data.data }
async function loadBackups() { backups.value = (await client.get('/ops/backups')).data.data }

async function createFault() {
  if (!fault.title) { ElMessage.warning('请填写完整'); return }
  createFaultLoading.value = true
  try {
    await client.post('/ops/faults', fault)
    fault.title = ''
    ElMessage.success('已登记')
    await loadFaults()
  } finally { createFaultLoading.value = false }
}

async function resolveFault(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('处理说明', '标记解决', { inputValue: '已处理' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/ops/faults/${row.id}/resolve`, null, { params: { note: value } })
    await loadFaults()
  } finally { busyId.value = null }
}

async function addInspection() {
  if (!insp.item) { ElMessage.warning('请填写完整'); return }
  addInspectionLoading.value = true
  try {
    await client.post('/ops/inspections', insp)
    insp.item = ''
    insp.note = ''
    ElMessage.success('已登记')
    await loadInspections()
  } finally { addInspectionLoading.value = false }
}

watch(tab, (t) => {
  if (t === 'slow') loadSlow()
  if (t === 'faults') loadFaults()
  if (t === 'inspections') loadInspections()
  if (t === 'backups') loadBackups()
})
onMounted(loadHealth)
</script>
