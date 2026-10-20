<template>
  <el-card>
    <div class="toolbar">
      <h3>医生排班</h3>
      <div class="filters">
        <el-button size="small" :disabled="busy" @click="shiftWeek(-7)">上一周</el-button>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="busy" />
        <el-button size="small" :disabled="busy" @click="shiftWeek(7)">下一周</el-button>
        <el-select v-model="deptId" placeholder="全部科室" clearable style="width: 160px" :disabled="busy">
          <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button :disabled="busy || loading" @click="load">刷新</el-button>
        <el-button v-if="isAdmin" type="primary" :disabled="busy || loading || closing" @click="openCreate">新增排班</el-button>
      </div>
    </div>

    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="deptName" label="科室" min-width="120" />
      <el-table-column prop="doctorName" label="医生" min-width="100">
        <template #default="{ row }">{{ row.doctorName || '（普通号）' }}</template>
      </el-table-column>
      <el-table-column label="时段" width="80">
        <template #default="{ row }">{{ shiftNames[row.shift] }}</template>
      </el-table-column>
      <el-table-column label="号别" width="90">
        <template #default="{ row }">{{ row.regType === 'EXPERT' ? '专家号' : '普通号' }}</template>
      </el-table-column>
      <el-table-column prop="fee" label="挂号费" width="90" />
      <el-table-column prop="capacity" label="号源" width="70" />
      <el-table-column prop="booked" label="已挂" width="70" />
      <el-table-column prop="remaining" label="余号" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停诊' }}</el-tag></template>
      </el-table-column>
      <el-table-column v-if="isAdmin" label="操作" width="100">
        <template #default="{ row }">
          <el-button link :type="row.enabled ? 'danger' : 'primary'" :disabled="busy || loading" @click="toggle(row)">{{ row.enabled ? '停诊' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增排班" width="440px" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="closing = false">
      <el-form :model="form" label-width="90px" :disabled="busy">
        <el-form-item label="日期" required>
          <el-date-picker v-model="form.scheduleDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="科室" required>
          <el-select v-model="form.deptId" style="width: 100%">
            <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="医生">
          <el-select v-model="form.doctorId" clearable placeholder="不选则为科室普通号" style="width: 100%">
            <el-option v-for="u in doctors" :key="u.id" :label="u.realName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时段">
          <el-radio-group v-model="form.shift">
            <el-radio value="AM">上午</el-radio>
            <el-radio value="PM">下午</el-radio>
            <el-radio value="FULL">全天</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="号别">
          <el-radio-group v-model="form.regType">
            <el-radio value="GENERAL">普通号</el-radio>
            <el-radio value="EXPERT">专家号</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="挂号费" required>
          <el-input-number v-model="form.fee" :min="0" :max="9999.99" :precision="2" />
        </el-form-item>
        <el-form-item label="号源数量" required>
          <el-input-number v-model="form.capacity" :min="1" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="closeCreate">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const isAdmin = computed(() => auth.user?.roles.includes('ADMIN') ?? false)
const shiftNames: Record<string, string> = { AM: '上午', PM: '下午', FULL: '全天' }
type Row = Record<string, unknown>
type Dept = { id: number; name: string; type: string; enabled: boolean }
type Doctor = { id: number; realName: string; enabled: boolean; roleCodes: string[] }
const date = ref(todayLocal())
const deptId = ref<number | null>(null)
const records = ref<Row[]>([])
const depts = ref<Dept[]>([])
const doctors = ref<Doctor[]>([])
const loading = ref(false)
const busy = ref(false)
const dialogVisible = ref(false)
const closing = ref(false)
let viewVersion = 0
let dialogVersion = -1
const clinicalDepts = computed(() => depts.value.filter(d => d.type === 'CLINICAL' && d.enabled))
const defaults = () => ({ scheduleDate: date.value, deptId: null as number | null, doctorId: null as number | null, shift: 'FULL', regType: 'GENERAL', fee: 10, capacity: 50 })
const form = reactive(defaults())
function resetForm() { dialogVersion = -1; Object.assign(form, defaults()) }
function closeCreate() { if (dialogVisible.value) closing.value = true; dialogVisible.value = false; resetForm() }
watch(dialogVisible, visible => { if (!visible) { closing.value = true; resetForm() } })
function clearAll() { ++viewVersion; records.value = []; depts.value = []; doctors.value = []; loading.value = false; closeCreate() }
function shiftWeek(days: number) {
  if (busy.value) return
  const d = new Date(date.value + 'T00:00:00'); d.setDate(d.getDate() + days)
  date.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
async function loadDoctors(version: number) {
  const result: Doctor[] = []
  for (let page = 0; version === viewVersion; page++) {
    const response = await client.get('/system/users', { params: { page, size: 100 } })
    const rows: Doctor[] = response.data.data.records
    result.push(...rows.filter(u => u.enabled && u.roleCodes.includes('DOCTOR_OUTP')))
    if (!rows.length || (page + 1) * 100 >= Number(response.data.data.total)) break
  }
  return result
}
async function load() {
  clearAll()
  const version = viewVersion, scheduleDate = date.value, department = deptId.value, admin = isAdmin.value
  loading.value = true
  try {
    const [r, d, u] = await Promise.all([
      client.get('/outpatient/schedules', { params: { date: scheduleDate, deptId: department ?? undefined, ...(admin ? { includeDisabled: true } : {}) } }),
      client.get('/system/depts'), admin ? loadDoctors(version) : Promise.resolve([]),
    ])
    if (version === viewVersion) { records.value = r.data.data; depts.value = d.data.data; doctors.value = u }
  } catch { if (version === viewVersion) clearAll() }
  finally { if (version === viewVersion) loading.value = false }
}
function openCreate() {
  if (!isAdmin.value || busy.value || loading.value || closing.value || dialogVisible.value) return
  resetForm(); form.deptId = clinicalDepts.value.some(d => d.id === deptId.value) ? deptId.value : null
  dialogVersion = viewVersion; dialogVisible.value = true
}
async function save() {
  if (!isAdmin.value || busy.value || !dialogVisible.value || dialogVersion !== viewVersion) return
  if (!form.deptId || !clinicalDepts.value.some(d => d.id === form.deptId)) { ElMessage.warning('请选择科室'); return }
  if (form.doctorId && !doctors.value.some(u => u.id === form.doctorId)) return
  const payload = { ...form }, version = viewVersion
  busy.value = true
  try {
    await client.post('/outpatient/schedules', payload)
    if (version === viewVersion) { ElMessage.success('排班已创建'); closeCreate(); await load() }
  } catch { if (version === viewVersion) clearAll() }
  finally { busy.value = false }
}
async function toggle(row: Row) {
  if (!isAdmin.value || busy.value || loading.value || !records.value.some(r => r.id === row.id)) return
  const id = Number(row.id), enabled = !row.enabled, version = viewVersion
  busy.value = true
  try {
    await client.put(`/outpatient/schedules/${id}/enabled`, null, { params: { enabled } })
    if (version === viewVersion) { ElMessage.success(enabled ? '已启用' : '已停诊'); await load() }
  } catch { if (version === viewVersion) clearAll() }
  finally { busy.value = false }
}
watch([date, deptId], load)
onBeforeUnmount(clearAll)
onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap; gap: 8px;
}
.toolbar h3 { margin: 0; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
