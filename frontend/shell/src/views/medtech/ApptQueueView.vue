<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="时段预约" name="appt">
        <h4>待预约申请单（已收费）
          <el-button link type="primary" size="small" @click="loadPending">刷新</el-button>
        </h4>
        <el-table :data="pending" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="order_type" label="类型" width="80" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column label="预约" width="330">
            <template #default="{ row }">
              <el-date-picker v-model="row._date" type="date" value-format="YYYY-MM-DD" size="small"
                              placeholder="日期" style="width: 130px" />
              <el-select v-model="row._period" size="small" style="width: 80px; margin: 0 4px">
                <el-option label="上午" value="AM" /><el-option label="下午" value="PM" />
              </el-select>
              <el-button type="primary" size="small" :loading="bookBusy === row.order_id" @click="book(row)">预约</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>
          预约队列
          <el-date-picker v-model="queryDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 140px; margin-left: 8px" @change="loadAppts" />
        </h4>
        <el-button size="small" type="danger" :disabled="!selection.length" :loading="batchCancelLoading" @click="batchCancel"
                   style="margin-bottom: 6px">批量取消（{{ selection.length }}）</el-button>
        <el-table :data="appts" size="small" border @selection-change="(s: Record<string, unknown>[]) => selection = s">
          <el-table-column type="selection" width="42" :selectable="(row: Record<string, unknown>) => row.status === 'BOOKED'" />
          <el-table-column prop="period" label="时段" width="70" />
          <el-table-column prop="seq_no" label="顺序号" width="80" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'BOOKED'" link type="warning" size="small" :loading="busyId === row.id" @click="reschedule(row)">
                改约</el-button>
              <el-button v-if="['BOOKED', 'CALLED'].includes(String(row.status))" link type="success" size="small"
                         :loading="busyId === row.id" @click="done(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane v-for="q in queues" :key="q.type" :label="q.label" :name="q.type">
        <el-button type="primary" size="small" :loading="callNextLoading" @click="callNext(q.type)">叫下一号</el-button>
        <el-row :gutter="16" style="margin-top: 10px">
          <el-col :span="12">
            <h4>等待中（{{ q.waiting.length }}）</h4>
            <el-table :data="q.waiting" size="small" border>
              <el-table-column prop="patient_name" label="患者" width="100" />
              <el-table-column prop="detail" label="内容" show-overflow-tooltip />
            </el-table>
          </el-col>
          <el-col :span="12">
            <h4>今日已叫</h4>
            <el-table :data="q.called" size="small" border>
              <el-table-column prop="patient_name" label="患者" width="100" />
              <el-table-column prop="called_at" label="叫号时间" />
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('appt')
const pending = ref<Record<string, unknown>[]>([])
const appts = ref<Record<string, unknown>[]>([])
const queryDate = ref(todayLocal())
const selection = ref<Record<string, unknown>[]>([])
const bookBusy = ref<unknown>(null)
const busyId = ref<unknown>(null)
const batchCancelLoading = ref(false)
const callNextLoading = ref(false)

const queues = reactive([
  { type: 'PHARMACY', label: '药房取药叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'EXAM', label: '检查叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'LAB', label: '检验叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
])

async function loadPending() { pending.value = (await client.get('/appointments/pending')).data.data }
async function loadAppts() { appts.value = (await client.get('/appointments', { params: { date: queryDate.value } })).data.data }
async function loadQueue(type: string) {
  const q = queues.find((x) => x.type === type)
  if (!q) return
  const d = (await client.get(`/queue-center/${type}`)).data.data
  q.waiting = d.waiting
  q.called = d.called
}

async function book(row: Record<string, unknown>) {
  if (!row._date || !row._period) return ElMessage.warning('请选择日期与时段')
  bookBusy.value = row.order_id
  try {
    const resp = await client.post('/appointments', { orderId: row.order_id, slotDate: row._date, period: row._period })
    ElMessage.success(`预约成功，顺序号 ${resp.data.data.seqNo}`)
    await Promise.all([loadPending(), loadAppts()])
  } finally { bookBusy.value = null }
}
async function done(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/appointments/${row.id}/done`)
    await loadAppts()
  } finally { busyId.value = null }
}
async function reschedule(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('新时段：日期,AM/PM（逗号分隔）', '改约',
    { inputValue: `${queryDate.value},PM` }).catch(() => null)
  if (!res) return
  const { value } = res
  const [slotDate, period] = value.split(/[,，]/).map((s: string) => s.trim())
  busyId.value = row.id
  try {
    const resp = await client.put(`/appointments/${row.id}/reschedule`, { slotDate, period })
    ElMessage.success(`已改约，新顺序号 ${resp.data.data.seqNo}`)
    await loadAppts()
  } finally { busyId.value = null }
}
async function batchCancel() {
  batchCancelLoading.value = true
  try {
    const resp = await client.post('/appointments/batch-cancel', { ids: selection.value.map((s) => s.id) })
    ElMessage.success(`已取消 ${resp.data.data.cancelled} 条`)
    selection.value = []
    await Promise.all([loadPending(), loadAppts()])
  } finally { batchCancelLoading.value = false }
}
async function callNext(type: string) {
  callNextLoading.value = true
  try {
    const resp = await client.post(`/queue-center/${type}/call-next`)
    ElMessage.success(`请 ${resp.data.data.patient_name} 就位`)
    await loadQueue(type)
  } finally { callNextLoading.value = false }
}

watch(tab, (t) => { if (t !== 'appt') loadQueue(t) })
onMounted(async () => { await Promise.all([loadPending(), loadAppts()]) })
</script>
