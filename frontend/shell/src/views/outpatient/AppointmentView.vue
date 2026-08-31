<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">分时段预约挂号</h3>
      <div style="display:flex; gap:8px">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width:140px" @change="loadSchedules" />
        <el-button size="small" @click="loadSchedules">刷新</el-button>
      </div>
    </div>

    <el-table :data="schedules" size="small" border highlight-current-row @current-change="pickSchedule">
      <el-table-column prop="id" label="排班" width="70" />
      <el-table-column prop="deptName" label="科室" width="120" />
      <el-table-column prop="fee" label="挂号费" width="80" />
      <el-table-column label="总号量" width="110">
        <template #default="{ row }">{{ row.booked }}/{{ row.capacity }}</template>
      </el-table-column>
    </el-table>

    <template v-if="scheduleId">
      <div class="toolbar" style="margin-top:14px">
        <h4 style="margin:0">时段（排班 {{ scheduleId }}）</h4>
        <el-button type="primary" size="small" @click="slotDialog = true">设时段</el-button>
      </div>
      <el-table :data="slots" size="small" border>
        <el-table-column label="时段" width="150">
          <template #default="{ row }">{{ String(row.time_begin).slice(0,5) }} ~ {{ String(row.time_end).slice(0,5) }}</template>
        </el-table-column>
        <el-table-column label="余号" width="90">
          <template #default="{ row }">
            <el-tag :type="row.remaining > 0 ? 'success' : 'info'" size="small">{{ row.remaining }}/{{ row.capacity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="row.remaining <= 0" @click="openBook(row)">预约</el-button>
          </template>
        </el-table-column>
      </el-table>

      <h4 style="margin-top:14px">预约名单（签到台）</h4>
      <el-table :data="appointments" size="small" border>
        <el-table-column prop="appt_no" label="号序" width="70" />
        <el-table-column prop="patient_name" label="患者" width="100" />
        <el-table-column label="时段" width="140">
          <template #default="{ row }">{{ String(row.time_begin).slice(0,5) }} ~ {{ String(row.time_end).slice(0,5) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="{ BOOKED: 'warning', CHECKED_IN: 'success', CANCELLED: 'info' }[row.status as string]" size="small">
              {{ { BOOKED: '已预约', CHECKED_IN: '已签到', CANCELLED: '已取消' }[row.status as string] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.status === 'BOOKED'" link type="success" size="small" @click="checkin(row)">签到挂号</el-button>
            <el-button v-if="row.status === 'BOOKED'" link type="danger" size="small" @click="cancelAppt(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="slotDialog" title="设时段（容量合计不得超排班总号量）" width="520px">
      <el-table :data="slotForm" size="small">
        <el-table-column label="开始"><template #default="{ row }"><el-input v-model="row.timeBegin" size="small" placeholder="08:00" /></template></el-table-column>
        <el-table-column label="结束"><template #default="{ row }"><el-input v-model="row.timeEnd" size="small" placeholder="08:30" /></template></el-table-column>
        <el-table-column label="号量" width="110"><template #default="{ row }"><el-input-number v-model="row.capacity" :min="1" :max="200" size="small" /></template></el-table-column>
      </el-table>
      <el-button size="small" style="margin-top:6px" @click="slotForm.push({ timeBegin: '', timeEnd: '', capacity: 5 })">加一行</el-button>
      <template #footer>
        <el-button @click="slotDialog = false">取消</el-button>
        <el-button type="primary" @click="saveSlots">保存时段</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bookDialog" title="预约" width="420px">
      <el-select v-model="bookPatientId" filterable remote :remote-method="searchPatients" placeholder="搜索患者" style="width:100%">
        <el-option v-for="p in patients" :key="p.id" :label="`${p.patientNo} ${p.name}`" :value="p.id" />
      </el-select>
      <template #footer>
        <el-button @click="bookDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!bookPatientId" @click="book">预约</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { todayLocal } from '../../utils/date'

const date = ref(todayLocal())
const schedules = ref<Record<string, unknown>[]>([])
const scheduleId = ref<number | null>(null)
const slots = ref<Record<string, unknown>[]>([])
const appointments = ref<Record<string, unknown>[]>([])
const slotDialog = ref(false)
const slotForm = ref<{ timeBegin: string; timeEnd: string; capacity: number }[]>([{ timeBegin: '08:00', timeEnd: '08:30', capacity: 5 }])
const bookDialog = ref(false)
const bookSlot = ref<Record<string, unknown> | null>(null)
const bookPatientId = ref<number | null>(null)
const patients = ref<Record<string, unknown>[]>([])

async function loadSchedules() {
  schedules.value = (await client.get('/outpatient/schedules', { params: { date: date.value } })).data.data
  scheduleId.value = null
}
async function pickSchedule(row: Record<string, unknown> | null) {
  scheduleId.value = row ? Number(row.id) : null
  if (scheduleId.value) await Promise.all([loadSlots(), loadAppointments()])
}
async function loadSlots() { slots.value = (await client.get(`/outpatient/schedules/${scheduleId.value}/slots`)).data.data }
async function loadAppointments() { appointments.value = (await client.get('/outpatient/appointments', { params: { scheduleId: scheduleId.value } })).data.data }

async function saveSlots() {
  const rows = slotForm.value.filter(s => s.timeBegin && s.timeEnd)
  if (!rows.length) { ElMessage.warning('请填时段'); return }
  await client.post(`/outpatient/schedules/${scheduleId.value}/slots`, { slots: rows })
  ElMessage.success('时段已保存')
  slotDialog.value = false
  await loadSlots()
}

async function searchPatients(kw: string) {
  if (!kw) return
  patients.value = (await client.get('/patients', { params: { keyword: kw } })).data.data
}
function openBook(row: Record<string, unknown>) { bookSlot.value = row; bookPatientId.value = null; bookDialog.value = true }
async function book() {
  await client.post('/outpatient/appointments', { slotId: bookSlot.value!.id, patientId: bookPatientId.value, source: '窗口' })
  ElMessage.success('预约成功')
  bookDialog.value = false
  await Promise.all([loadSlots(), loadAppointments()])
}
async function checkin(row: Record<string, unknown>) {
  const r = (await client.post(`/outpatient/appointments/${row.id}/checkin`)).data.data
  ElMessage.success(`签到成功，号序 ${r.regNo}`)
  await loadAppointments()
}
async function cancelAppt(row: Record<string, unknown>) {
  await ElMessageBox.confirm('取消该预约？号源将释放。', '确认', { type: 'warning' }).catch(() => null)
    .then(async (ok) => { if (ok) { await client.post(`/outpatient/appointments/${row.id}/cancel`); ElMessage.success('已取消'); await Promise.all([loadSlots(), loadAppointments()]) } })
}

onMounted(loadSchedules)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
</style>
