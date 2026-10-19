<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">知情同意书 / 授权委托书</h3>
      <div style="display:flex; gap:8px; align-items:center">
        <el-radio-group v-model="careType" size="small" @change="changeScope"><el-radio-button value="INP">住院</el-radio-button><el-radio-button value="OUTP">门诊</el-radio-button></el-radio-group>
        <el-select v-if="careType === 'INP'" v-model="admissionId" placeholder="选择在院患者" filterable style="width:240px" @change="loadConsents">
          <el-option v-for="a in admissions" :key="a.id" :label="`${a.admissionNo} ${a.patientName}`" :value="a.id" />
        </el-select>
        <template v-else>
          <el-date-picker v-model="visitDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:145px" @change="loadRegistrations" />
          <el-select v-model="registrationId" placeholder="选择门诊就诊" filterable style="width:310px" @change="loadConsents">
            <el-option v-for="r in registrations" :key="Number(r.id)" :value="Number(r.id)"
              :label="`${r.visit_date} ${r.reg_no}号 ${r.patient_name}（${r.patient_no}）`" />
          </el-select>
        </template>
        <el-button size="small" @click="refresh">刷新</el-button>
        <el-button type="primary" size="small" :disabled="!selectedId" @click="openCreate">新建同意书</el-button>
      </div>
    </div>

    <el-table :data="consents" size="small" border>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeCn[row.consent_type as string] ?? row.consent_type }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status as string]" size="small">{{ statusCn[row.status as string] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" :loading="busy === Number(row.id)" @click="patientSign(row)">患者签名</el-button>
          <el-button v-if="canDoctorSign && row.status === 'PATIENT_SIGNED'" link type="success" size="small" :loading="busy === Number(row.id)" @click="doctorSign(row)">医师签名</el-button>
          <el-button v-if="row.status !== 'REVOKED' && row.status !== 'SIGNED'" link type="danger" size="small" :loading="busy === Number(row.id)" @click="revoke(row)">作废</el-button>
          <el-tag v-if="row.status === 'SIGNED'" type="success" size="small" effect="plain">已生效</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建知情同意书" width="600px">
      <el-form :model="form" label-width="100px" size="small">
        <el-form-item label="类型" required>
          <el-select v-model="form.consentType" @change="onTypeChange">
            <el-option v-for="(v, k) in typeCn" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" placeholder="留空自动生成" /></el-form-item>
        <el-form-item label="内容" required><el-input v-model="form.content" type="textarea" :rows="5" /></el-form-item>
        <template v-if="form.consentType === 'PROXY'">
          <el-form-item label="委托人姓名" required><el-input v-model="form.agentName" /></el-form-item>
          <el-form-item label="与患者关系" required><el-input v-model="form.agentRelation" /></el-form-item>
          <el-form-item label="委托原因" required><el-input v-model="form.agentReason" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="create" :loading="saving">创建</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { todayLocal } from '../../utils/date'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canDoctorSign = computed(() => {
  if (auth.user?.roles.includes('ADMIN')) return true
  if (!auth.user?.roles.includes('DOCTOR_OUTP')) return false
  if (careType.value === 'INP') return true
  const row = registrations.value.find(r => Number(r.id) === registrationId.value)
  return !!row && (row.visit_date === todayLocal() || row.doctor_id == null
    || Number(row.doctor_id) === auth.user.id || Number(row.emr_doctor_id) === auth.user.id)
})

const typeCn: Record<string, string> = {
  SURGERY: '手术', TRANSFUSION: '输血', ANESTHESIA: '麻醉',
  SPECIAL_EXAM: '特殊检查', SELF_PAY: '自费', PROXY: '授权委托',
}
const statusCn: Record<string, string> = { DRAFT: '待患者签', PATIENT_SIGNED: '待医师签', SIGNED: '已生效', REVOKED: '已作废' }
const statusTag: Record<string, string> = { DRAFT: 'info', PATIENT_SIGNED: 'warning', SIGNED: 'success', REVOKED: 'danger' }

const careType = ref('INP')
const admissions = ref<Record<string, unknown>[]>([])
const admissionId = ref<number | null>(null)
const visitDate = ref(todayLocal())
const registrations = ref<Record<string, unknown>[]>([])
const registrationId = ref<number | null>(null)
const selectedId = computed(() => careType.value === 'INP' ? admissionId.value : registrationId.value)
const selectedKey = computed(() => `${careType.value}:${selectedId.value}`)
const consents = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
const busy = ref<number | null>(null)
const form = reactive({ consentType: 'SURGERY', title: '', content: '', agentName: '', agentRelation: '', agentReason: '' })
let consentLoad = 0, registrationLoad = 0, creationKey = ''
let creationParent: { admissionId?: number; registrationId?: number } = {}
function clearConsents() { ++consentLoad; consents.value = []; dialogVisible.value = false; creationKey = '' }
function parentParams() { return careType.value === 'INP' ? { admissionId: admissionId.value } : { registrationId: registrationId.value } }
async function loadAdmissions() {
  admissions.value = (await client.get('/inpatient/admissions')).data.data
    .filter((a: Record<string, unknown>) => a.status === 'IN_HOSPITAL')
}
async function loadRegistrations() {
  const request = ++registrationLoad; registrations.value = []; registrationId.value = null; clearConsents()
  const date = visitDate.value
  if (!date) return
  const rows = (await client.get('/emr/consents/registrations', { params: { date } })).data.data
  if (request === registrationLoad && careType.value === 'OUTP' && date === visitDate.value) registrations.value = rows
}
async function changeScope() {
  ++registrationLoad; admissionId.value = null; registrationId.value = null; clearConsents()
  if (careType.value === 'OUTP') await loadRegistrations(); else await loadAdmissions()
}
async function refresh() {
  if (careType.value === 'OUTP') await loadRegistrations(); else { clearConsents(); admissionId.value = null; await loadAdmissions() }
}
async function loadConsents() {
  clearConsents(); const request = consentLoad, key = selectedKey.value
  if (!selectedId.value) return
  const rows = (await client.get('/emr/consents', { params: parentParams() })).data.data
  if (request === consentLoad && key === selectedKey.value) consents.value = rows
}
function openCreate() {
  if (!selectedId.value) return
  Object.assign(form, { consentType: 'SURGERY', title: '', content: '', agentName: '', agentRelation: '', agentReason: '' })
  creationKey = selectedKey.value
  creationParent = careType.value === 'INP' ? { admissionId: selectedId.value } : { registrationId: selectedId.value }
  dialogVisible.value = true
}
function onTypeChange() { /* PROXY fields are rendered by the template. */ }
async function create() {
  if (saving.value || !selectedId.value || creationKey !== selectedKey.value) return
  if (!form.content.trim()) { ElMessage.warning('内容必填'); return }
  const parent = { ...creationParent }, key = creationKey
  saving.value = true
  try {
    await client.post('/emr/consents', { ...parent, ...form })
    ElMessage.success('已创建（待患者签名）')
    if (key === selectedKey.value) await loadConsents()
  } catch (error) { clearConsents(); throw error }
  finally { saving.value = false }
}
function stillSelected(row: Record<string, unknown>, key: string) {
  return selectedKey.value === key && consents.value.some(x => x.id === row.id)
}
async function mutate(row: Record<string, unknown>, key: string, action: () => Promise<unknown>) {
  if (busy.value != null || !stillSelected(row, key)) return
  busy.value = Number(row.id)
  try { await action(); if (key === selectedKey.value) await loadConsents() }
  catch (error) { clearConsents(); throw error }
  finally { busy.value = null }
}
async function patientSign(row: Record<string, unknown>) {
  const key = selectedKey.value
  const res = await ElMessageBox.prompt('患者/委托人签名（录入签名标识）', '患者签名',
    { inputPlaceholder: '如：患者本人 张三' }).catch(() => null)
  if (!res) return
  await mutate(row, key, async () => { await client.post(`/emr/consents/${row.id}/patient-sign`, { patientSign: res.value }); ElMessage.success('患者已签名') })
}
async function doctorSign(row: Record<string, unknown>) {
  if (!canDoctorSign.value) return
  await mutate(row, selectedKey.value, async () => { await client.post(`/emr/consents/${row.id}/doctor-sign`, {}); ElMessage.success('医师 CA 已签名，同意书生效') })
}
async function revoke(row: Record<string, unknown>) {
  const key = selectedKey.value
  const yes = await ElMessageBox.confirm('作废该同意书？', '确认', { type: 'warning' }).catch(() => false)
  if (yes) await mutate(row, key, async () => { await client.put(`/emr/consents/${row.id}/revoke`); ElMessage.success('已作废') })
}

onMounted(loadAdmissions)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
</style>
