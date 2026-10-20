<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">分时段预约挂号</h3>
      <div style="display:flex; gap:8px">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width:140px" :clearable="false" :disabled="busy" />
        <el-button size="small" :disabled="busy" @click="loadSchedules">刷新</el-button>
      </div>
    </div>

    <el-table :data="schedules" v-loading="loadingSchedules" size="small" border highlight-current-row @current-change="pickSchedule">
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
        <el-button v-if="isAdmin" type="primary" size="small" :disabled="busy || loadingDetail || slotClosing" @click="openSlots">设时段</el-button>
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
            <el-button link type="primary" size="small" :disabled="busy || loadingDetail || bookClosing || !row.enabled || row.remaining <= 0" @click="openBook(row)">预约</el-button>
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
            <el-button v-if="row.status === 'BOOKED'" link type="success" size="small" :disabled="busy || loadingDetail" @click="changeAppointment(row, true)">签到挂号</el-button>
            <el-button v-if="row.status === 'BOOKED'" link type="danger" size="small" :disabled="busy || loadingDetail" @click="changeAppointment(row, false)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="slotDialog" title="设时段（容量合计不得超排班总号量）" width="520px" @closed="slotClosing = false">
      <el-table :data="slotForm" size="small">
        <el-table-column label="开始"><template #default="{ row }"><el-input v-model="row.timeBegin" size="small" placeholder="08:00" /></template></el-table-column>
        <el-table-column label="结束"><template #default="{ row }"><el-input v-model="row.timeEnd" size="small" placeholder="08:30" /></template></el-table-column>
        <el-table-column label="号量" width="110"><template #default="{ row }"><el-input-number v-model="row.capacity" :min="1" :max="200" size="small" /></template></el-table-column>
      </el-table>
      <el-button size="small" style="margin-top:6px" @click="slotForm.push({ timeBegin: '', timeEnd: '', capacity: 5 })">加一行</el-button>
      <template #footer>
        <el-button @click="closeSlots">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveSlots">保存时段</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bookDialog" title="预约" width="420px" @closed="bookClosing = false">
      <el-select v-model="bookPatientId" filterable remote :remote-method="searchPatients" placeholder="搜索患者" style="width:100%">
        <el-option v-for="p in patients" :key="p.id" :label="`${p.patientNo} ${p.name}`" :value="p.id" />
      </el-select>
      <template #footer>
        <el-button @click="closeBook">取消</el-button>
        <el-button type="primary" :disabled="!bookPatientId || busy" :loading="busy" @click="book">预约</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { todayLocal } from '../../utils/date'
import { useAuthStore } from '../../stores/auth'
const auth = useAuthStore()
const isAdmin = computed(() => auth.user?.roles.includes('ADMIN') ?? false)
type Row = Record<string, unknown>
type SlotLine = { timeBegin: string; timeEnd: string; capacity: number }
const defaultSlots = (): SlotLine[] => [{ timeBegin: '08:00', timeEnd: '08:30', capacity: 5 }]
const date = ref(todayLocal())
const schedules = ref<Row[]>([])
const scheduleId = ref<number | null>(null)
const slots = ref<Row[]>([])
const appointments = ref<Row[]>([])
const slotDialog = ref(false)
const slotClosing = ref(false)
const slotForm = ref<SlotLine[]>(defaultSlots())
const bookDialog = ref(false)
const bookClosing = ref(false)
const bookTarget = ref<{ schedule: number; slot: number; view: number } | null>(null)
const bookPatientId = ref<number | null>(null)
const patients = ref<Row[]>([])
const loadingSchedules = ref(false)
const loadingDetail = ref(false)
const busy = ref(false)
let viewVersion = 0
let searchVersion = 0

function resetBook() { ++searchVersion; bookTarget.value = null; bookPatientId.value = null; patients.value = [] }
watch(bookDialog, visible => { if (!visible) { bookClosing.value = true; resetBook() } })
watch(slotDialog, visible => { if (!visible) { slotClosing.value = true; slotForm.value = defaultSlots() } })
function closeBook() { if (bookDialog.value) bookClosing.value = true; bookDialog.value = false; resetBook() }
function closeSlots() { if (slotDialog.value) slotClosing.value = true; slotDialog.value = false; slotForm.value = defaultSlots() }
function clearSelection() { ++viewVersion; scheduleId.value = null; slots.value = []; appointments.value = []; loadingDetail.value = false; closeBook(); closeSlots() }
function clearAll() { clearSelection(); schedules.value = []; loadingSchedules.value = false }
function current(id: number, version: number) { return scheduleId.value === id && viewVersion === version }
async function loadSchedules() {
  clearAll(); const version = viewVersion; loadingSchedules.value = true
  try {
    const resp = await client.get('/outpatient/schedules', { params: { date: date.value } })
    if (version === viewVersion) schedules.value = resp.data.data
  } catch { if (version === viewVersion) clearAll() }
  finally { if (version === viewVersion) loadingSchedules.value = false }
}
async function loadDetail(id: number, version: number) {
  if (!current(id, version)) return
  slots.value = []; appointments.value = []; loadingDetail.value = true
  try {
    const [slotResp, apptResp, scheduleResp] = await Promise.all([
      client.get(`/outpatient/schedules/${id}/slots`), client.get('/outpatient/appointments', { params: { scheduleId: id } }),
      client.get('/outpatient/schedules', { params: { date: date.value } }),
    ])
    if (current(id, version)) {
      const latest: Row[] = scheduleResp.data.data
      if (!latest.some(s => Number(s.id) === id)) { clearAll(); return }
      schedules.value = latest; slots.value = slotResp.data.data; appointments.value = apptResp.data.data
    }
  } catch { if (current(id, version)) clearSelection() }
  finally { if (current(id, version)) loadingDetail.value = false }
}
async function pickSchedule(row: Row | null) {
  // Clearing the table during a reload emits current-change(null); it must not cancel that reload.
  if (busy.value || !row) return
  clearSelection()
  if (!schedules.value.some(s => s.id === row.id)) return
  scheduleId.value = Number(row.id)
  await loadDetail(scheduleId.value, viewVersion)
}
function openSlots() {
  if (!isAdmin.value || busy.value || loadingDetail.value || slotClosing.value || slotDialog.value || !scheduleId.value) return
  slotForm.value = defaultSlots(); slotDialog.value = true
}
async function saveSlots() {
  if (!isAdmin.value || busy.value || !slotDialog.value || !scheduleId.value) return
  const id = scheduleId.value, version = viewVersion
  const rows = slotForm.value.map(s => ({ ...s })).filter(s => s.timeBegin && s.timeEnd)
  if (!rows.length) { ElMessage.warning('请填时段'); return }
  busy.value = true
  try {
    await client.post(`/outpatient/schedules/${id}/slots`, { slots: rows })
    ElMessage.success('时段已保存')
    if (current(id, version)) { closeSlots(); await loadDetail(id, version) }
  } catch { if (current(id, version)) clearSelection() }
  finally { busy.value = false }
}
function openBook(row: Row) {
  if (busy.value || loadingDetail.value || bookClosing.value || bookDialog.value || !scheduleId.value || !row.enabled || Number(row.remaining) <= 0 || !slots.value.some(s => s.id === row.id)) return
  resetBook(); bookTarget.value = { schedule: scheduleId.value, slot: Number(row.id), view: viewVersion }; bookDialog.value = true
}
async function searchPatients(kw: string) {
  const seq = ++searchVersion, target = bookTarget.value
  patients.value = []; bookPatientId.value = null
  if (!kw.trim() || !bookDialog.value || !target) return
  try {
    const resp = await client.get('/patients', { params: { keyword: kw, page: 0, size: 20 } })
    if (seq === searchVersion && bookDialog.value && bookTarget.value === target) patients.value = resp.data.data.records
  } catch { if (seq === searchVersion) { patients.value = []; bookPatientId.value = null } }
}
async function book() {
  const target = bookTarget.value, patient = bookPatientId.value
  if (busy.value || !bookDialog.value || !target || !patient || !current(target.schedule, target.view) || !patients.value.some(p => p.id === patient)) return
  busy.value = true
  try {
    await client.post('/outpatient/appointments', { slotId: target.slot, patientId: patient, source: '窗口' })
    ElMessage.success('预约成功')
    if (current(target.schedule, target.view)) { closeBook(); await loadDetail(target.schedule, target.view) }
  } catch { if (current(target.schedule, target.view)) clearSelection() }
  finally { busy.value = false }
}
async function changeAppointment(row: Row, checkin: boolean) {
  if (busy.value || loadingDetail.value || !scheduleId.value || row.status !== 'BOOKED' || !appointments.value.some(a => a.id === row.id)) return
  const id = scheduleId.value, version = viewVersion, appointment = Number(row.id)
  busy.value = true
  try {
    if (!checkin) {
      try { await ElMessageBox.confirm('取消该预约？号源将释放。', '确认', { type: 'warning' }) } catch { return }
      if (!current(id, version)) return
    }
    const resp = await client.post(`/outpatient/appointments/${appointment}/${checkin ? 'checkin' : 'cancel'}`)
    ElMessage.success(checkin ? `签到成功，号序 ${resp.data.data.regNo}` : '已取消')
    if (current(id, version)) await loadDetail(id, version)
  } catch { if (current(id, version)) clearSelection() }
  finally { busy.value = false }
}
watch(date, loadSchedules)
onBeforeUnmount(clearAll)
onMounted(loadSchedules)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
</style>
