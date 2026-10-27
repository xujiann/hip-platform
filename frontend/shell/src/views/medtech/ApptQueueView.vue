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
                          style="width: 140px; margin-left: 8px" @change="loadAppts()" />
          <el-button link type="primary" size="small" @click="loadAppts()">刷新</el-button>
        </h4>
        <el-button size="small" type="danger" :disabled="!appointmentsReady || !selection.length || selection.some(isAppointmentBusy)" :loading="batchCancelLoading" @click="batchCancel"
                   style="margin-bottom: 6px">批量取消（{{ selection.length }}）</el-button>
        <el-table v-if="appointmentsReady" :data="appts" size="small" border @selection-change="onAppointmentSelection">
          <el-table-column type="selection" width="42" :selectable="appointmentSelectable" />
          <el-table-column prop="slot_date" label="日期" width="110" />
          <el-table-column prop="period" label="时段" width="70" />
          <el-table-column prop="seq_no" label="顺序号" width="80" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'BOOKED'" link type="warning" size="small" :loading="busyId === row.id" :disabled="isAppointmentBusy(row)" @click="reschedule(row)">
                改约</el-button>
              <el-button v-if="['BOOKED', 'CALLED'].includes(String(row.status))" link type="success" size="small"
                         :loading="busyId === row.id" :disabled="isAppointmentBusy(row)" @click="done(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="appointmentsLoading ? '正在加载该日期预约，请稍候' : '预约队列未加载，请刷新重试'" :image-size="60" />
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
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { type BizError } from '../../api/client'

const tab = ref('appt')
const pending = ref<Record<string, unknown>[]>([])
const appts = ref<Record<string, unknown>[]>([])
const queryDate = ref<string | null>(todayLocal())
const selection = ref<Record<string, unknown>[]>([])
const bookBusy = ref<unknown>(null)
const callNextLoading = ref(false)
const appointmentsLoading = ref(false)
const appointmentsReady = ref(false)
let appointmentAlive = true
let appointmentGeneration = 0
let loadedAppointmentDate: string | null | undefined
const appointmentPendingIds = reactive(new Set<unknown>())
const rowWriteIds = reactive(new Set<unknown>())
const batchDates = reactive(new Set<string | null>())
const busyId = computed(() => appts.value.find(row => rowWriteIds.has(row.id))?.id ?? null)
const batchCancelLoading = computed(() => batchDates.has(queryDate.value))
type AppointmentView = { date: string | null; rows: Record<string, unknown>[]; valid: () => boolean }

function captureAppointmentView(requireReady = true): AppointmentView | null {
  if (!appointmentAlive || requireReady && (!appointmentsReady.value || loadedAppointmentDate !== queryDate.value)) return null
  const date = queryDate.value, generation = appointmentGeneration, rows = appts.value
  return { date, rows, valid: () => appointmentAlive && generation === appointmentGeneration && date === queryDate.value
    && (!requireReady || appointmentsReady.value && loadedAppointmentDate === date && appts.value === rows) }
}
function clearAppointments() {
  appointmentGeneration++
  appointmentsReady.value = false
  appointmentsLoading.value = false
  loadedAppointmentDate = undefined
  appts.value = []
  selection.value = []
}
function isAppointmentBusy(row: Record<string, unknown>) { return appointmentPendingIds.has(row.id) }
function appointmentSelectable(row: Record<string, unknown>) {
  return appointmentsReady.value && appts.value.includes(row) && row.status === 'BOOKED' && !isAppointmentBusy(row)
}
function onAppointmentSelection(rows: Record<string, unknown>[]) {
  if (!appointmentsReady.value || !rows.every(appointmentSelectable)) return
  selection.value = rows
}
function rowBelongsToView(context: AppointmentView, row: Record<string, unknown>, statuses: string[]) {
  return context.valid() && context.rows.includes(row) && statuses.includes(String(row.status))
}
function occupyAppointments(ids: unknown[]) {
  if (ids.some(id => appointmentPendingIds.has(id))) return null
  for (const id of ids) appointmentPendingIds.add(id)
  return () => { for (const id of ids) appointmentPendingIds.delete(id) }
}

const queues = reactive([
  { type: 'PHARMACY', label: '药房取药叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'EXAM', label: '检查叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'LAB', label: '检验叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
])

async function loadPending() {
  const response = await client.get('/appointments/pending')
  if (appointmentAlive) pending.value = response.data.data
}
async function loadAppts(context?: AppointmentView) {
  if (!appointmentAlive || context && !context.valid()) return
  clearAppointments()
  const date = queryDate.value, generation = appointmentGeneration
  const valid = () => appointmentAlive && generation === appointmentGeneration && date === queryDate.value
  appointmentsLoading.value = true
  try {
    const response = await client.get('/appointments', { params: { date } })
    if (!valid()) return
    appts.value = response.data.data
    loadedAppointmentDate = date
    appointmentsReady.value = true
  } catch (error) { if (valid()) throw error }
  finally { if (valid()) appointmentsLoading.value = false }
}
async function loadQueue(type: string) {
  const q = queues.find((x) => x.type === type)
  if (!q) return
  const d = (await client.get(`/queue-center/${type}`)).data.data
  q.waiting = d.waiting
  q.called = d.called
}

async function book(row: Record<string, unknown>) {
  const context = captureAppointmentView(false); if (!context) return
  if (!row._date || !row._period) return ElMessage.warning('请选择日期与时段')
  bookBusy.value = row.order_id
  try {
    const resp = await client.post('/appointments', { orderId: row.order_id, slotDate: row._date, period: row._period })
    if (!appointmentAlive) return
    ElMessage.success(`预约成功，顺序号 ${resp.data.data.seqNo}`)
    await Promise.all([loadPending(), loadAppts(context)])
  } catch (error) {
    // The shared client already showed this expected source-eligibility refusal.
    if ((error as BizError)?.bizCode !== 4510) throw error
  } finally { bookBusy.value = null }
}
function isAppointmentPermissionDenied(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('response' in error)) return false
  const response = error.response
  if (typeof response !== 'object' || response === null
      || !('status' in response) || response.status !== 403 || !('data' in response)) return false
  const data = response.data
  return typeof data === 'object' && data !== null && 'code' in data && data.code === 1005
}
async function done(row: Record<string, unknown>) {
  const context = captureAppointmentView(); if (!context || !rowBelongsToView(context, row, ['BOOKED', 'CALLED'])) return
  const id = row.id, release = occupyAppointments([id]); if (!release) return
  rowWriteIds.add(id)
  try {
    try { await client.put(`/appointments/${id}/done`) }
    catch (error) { if (!context.valid() || isAppointmentPermissionDenied(error)) return; throw error }
    if (!context.valid()) return
    await loadAppts(context)
  } finally { rowWriteIds.delete(id); release() }
}
async function reschedule(row: Record<string, unknown>) {
  const context = captureAppointmentView(); if (!context || !rowBelongsToView(context, row, ['BOOKED'])) return
  const id = row.id, release = occupyAppointments([id]); if (!release) return
  rowWriteIds.add(id)
  try {
    const res = await ElMessageBox.prompt('新时段：日期,AM/PM（逗号分隔）', '改约',
      { inputValue: `${context.date ?? ''},PM` }).catch(() => null)
    if (!res || !rowBelongsToView(context, row, ['BOOKED']) || row.id !== id) return
    const [slotDate, period] = res.value.split(/[,，]/).map((s: string) => s.trim())
    let resp
    try { resp = await client.put(`/appointments/${id}/reschedule`, { slotDate, period }) }
    catch (error) { if (!context.valid() || (error as BizError)?.bizCode === 4513) return; throw error }
    if (!context.valid()) return
    ElMessage.success(`已改约，新顺序号 ${resp.data.data.seqNo}`)
    await loadAppts(context)
  } finally { rowWriteIds.delete(id); release() }
}
async function batchCancel() {
  const context = captureAppointmentView(), rows = [...selection.value]
  if (!context || batchDates.has(context.date) || !rows.every(row => rowBelongsToView(context, row, ['BOOKED']))) return
  const ids = rows.map(row => row.id), release = occupyAppointments(ids); if (!release) return
  batchDates.add(context.date)
  try {
    let resp
    try { resp = await client.post('/appointments/batch-cancel', { ids }) }
    catch (error) { if (!context.valid() || isAppointmentPermissionDenied(error)) return; throw error }
    if (!appointmentAlive) return
    if (context.valid()) {
      ElMessage.success(`已取消 ${resp.data.data.cancelled} 条`)
      selection.value = []
    }
    await Promise.all([loadPending(), loadAppts(context)])
  } finally { batchDates.delete(context.date); release() }
}
async function callNext(type: string) {
  callNextLoading.value = true
  try {
    const resp = await client.post(`/queue-center/${type}/call-next`)
    ElMessage.success(`请 ${resp.data.data.patient_name} 就位`)
    await loadQueue(type)
  } finally { callNextLoading.value = false }
}

watch(queryDate, clearAppointments, { flush: 'sync' })
watch(tab, (t) => { if (t !== 'appt') loadQueue(t) })
onUnmounted(() => { appointmentAlive = false; clearAppointments() })
onMounted(async () => { await Promise.all([loadPending(), loadAppts()]) })
</script>
