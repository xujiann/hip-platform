<template>
  <div class="register-page">
    <el-card class="panel">
      <template #header>1. 选择患者</template>
      <div class="search">
        <el-input v-model="patientKeyword" placeholder="姓名 / 患者号 / 身份证号 / 手机号" clearable :disabled="busy"
                  @keyup.enter="searchPatients" />
        <el-button type="primary" :disabled="busy" :loading="searching" @click="searchPatients">查询</el-button>
      </div>
      <el-table :data="patients" highlight-current-row height="220" @current-change="selectPatient">
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column label="性别" width="60">
          <template #default="{ row }">{{ { M: '男', F: '女', U: '未知' }[row.sex as string] }}</template>
        </el-table-column>
        <el-table-column prop="age" label="年龄" width="60" />
        <el-table-column prop="phone" label="手机号" />
      </el-table>
      <el-alert v-if="selectedPatient" type="success" :closable="false"
                :title="`已选：${selectedPatient.patientNo} ${selectedPatient.name}`" />
    </el-card>

    <el-card class="panel">
      <template #header>2. 选择号源（{{ date }}）</template>
      <div class="search">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="busy" />
      </div>
      <el-table :data="schedules" v-loading="loadingSchedules" height="260">
        <el-table-column prop="deptName" label="科室" width="120" />
        <el-table-column label="医生" width="100">
          <template #default="{ row }">{{ row.doctorName || '普通号' }}</template>
        </el-table-column>
        <el-table-column label="时段" width="70">
          <template #default="{ row }">{{ { AM: '上午', PM: '下午', FULL: '全天' }[row.shift as string] }}</template>
        </el-table-column>
        <el-table-column prop="fee" label="费用" width="70" />
        <el-table-column prop="remaining" label="余号" width="60" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button type="primary" size="small" :disabled="busy || loadingSchedules || row.remaining <= 0 || !selectedPatient"
                       @click="doRegister(row)">
              挂号
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="panel wide">
      <template #header><div class="records-header">今日挂号记录<el-button :disabled="busy || loadingRegs" @click="loadData">刷新</el-button></div></template>
      <el-table :data="registrations" v-loading="loadingRegs" height="240">
        <el-table-column prop="regNo" label="号序" width="60" />
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="deptName" label="科室" width="120" />
        <el-table-column label="医生" width="100">
          <template #default="{ row }">{{ row.doctorName || '普通号' }}</template>
        </el-table-column>
        <el-table-column prop="fee" label="费用" width="70" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status as string]" size="small">{{ statusNames[row.status as string] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <!-- v74 复核补入口：挂号凭条打印页（/print?type=registration）此前全前端零入口，
                 偏离表多行却写着「已提供挂号凭条打印」——后端做了、用户够不着。退号后的不再出凭条。 -->
            <el-button v-if="row.status !== 'CANCELLED'" link type="primary" :disabled="busy || loadingRegs" @click="printSlip(row)">凭条</el-button>
            <el-button v-if="row.status === 'REGISTERED'" link type="danger" :disabled="busy || loadingRegs" @click="doCancel(row)">退号</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import type { Patient, Registration, Schedule } from '../../types/domain'

const statusNames: Record<string, string> = { REGISTERED: '已挂号', CANCELLED: '已退号', VISITED: '已就诊' }
const statusTag: Record<string, string> = { REGISTERED: 'success', CANCELLED: 'info', VISITED: '' }
const date = ref(todayLocal())
const patientKeyword = ref('')
const patients = ref<Patient[]>([])
const selectedPatient = ref<Patient | null>(null)
type DeskSchedule = Schedule & { remaining: number }
const schedules = ref<DeskSchedule[]>([])
const registrations = ref<Registration[]>([])
const loadingSchedules = ref(false)
const loadingRegs = ref(false)
const searching = ref(false)
const busy = ref(false)
let viewVersion = 0
let searchVersion = 0

function clearPatient() { ++searchVersion; patients.value = []; selectedPatient.value = null; searching.value = false }
function clearAll() { ++viewVersion; clearPatient(); schedules.value = []; registrations.value = []; loadingSchedules.value = false; loadingRegs.value = false }
async function searchPatients() {
  if (busy.value) return
  clearPatient()
  const keyword = patientKeyword.value.trim(), seq = searchVersion, version = viewVersion
  if (!keyword) return
  searching.value = true
  try {
    const resp = await client.get('/patients', { params: { keyword, page: 0, size: 10 } })
    if (seq === searchVersion && version === viewVersion) patients.value = resp.data.data.records
  } catch { if (seq === searchVersion && version === viewVersion) clearAll() }
  finally { if (seq === searchVersion) searching.value = false }
}
function selectPatient(row: Patient | null) {
  if (busy.value && row) return
  selectedPatient.value = row && patients.value.some(p => p.id === row.id) ? row : null
}
async function loadData() {
  clearAll()
  const version = viewVersion, scheduleDate = date.value, recordsDate = todayLocal()
  loadingSchedules.value = true; loadingRegs.value = true
  try {
    const [slots, regs] = await Promise.all([
      client.get('/outpatient/schedules', { params: { date: scheduleDate } }),
      client.get('/outpatient/registrations', { params: { date: recordsDate } }),
    ])
    if (version === viewVersion) { schedules.value = slots.data.data; registrations.value = regs.data.data }
  } catch { if (version === viewVersion) clearAll() }
  finally { if (version === viewVersion) { loadingSchedules.value = false; loadingRegs.value = false } }
}
async function doRegister(schedule: DeskSchedule) {
  const patient = selectedPatient.value
  if (busy.value || loadingSchedules.value || !patient || !patients.value.some(p => p.id === patient.id) || schedule.remaining <= 0 || !schedules.value.some(s => s.id === schedule.id)) return
  const patientId = patient.id, scheduleId = schedule.id, version = viewVersion
  busy.value = true
  try {
    const resp = await client.post('/outpatient/registrations', { patientId, scheduleId })
    if (version === viewVersion) {
      const reg = resp.data.data
      ElMessage.success(`挂号成功：${reg.deptName} 第 ${reg.regNo} 号，费用 ${reg.fee} 元`)
      await loadData()
    }
  } catch { if (version === viewVersion) clearAll() }
  finally { busy.value = false }
}
function printSlip(row: Registration) {
  if (busy.value || loadingRegs.value || row.status === 'CANCELLED' || !registrations.value.some(r => r.id === row.id)) return
  window.open(`/print?type=registration&id=${row.id}`, '_blank')
}
async function doCancel(row: Registration) {
  if (busy.value || loadingRegs.value || row.status !== 'REGISTERED' || !registrations.value.some(r => r.id === row.id)) return
  const id = row.id, version = viewVersion
  busy.value = true
  try {
    try { await ElMessageBox.confirm(`确认为 ${row.patientName} 退号？`, '退号确认') } catch { return }
    if (version !== viewVersion) return
    await client.put(`/outpatient/registrations/${id}/cancel`)
    if (version === viewVersion) { ElMessage.success('已退号'); await loadData() }
  } catch { if (version === viewVersion) clearAll() }
  finally { busy.value = false }
}
watch(patientKeyword, clearPatient)
watch(date, loadData)
onBeforeUnmount(clearAll)
onMounted(loadData)
</script>

<style scoped>
.register-page {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.panel.wide { grid-column: span 2; }
.records-header { display: flex; justify-content: space-between; align-items: center; }
.search { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
