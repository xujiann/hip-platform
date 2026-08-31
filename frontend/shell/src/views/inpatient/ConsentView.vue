<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">知情同意书 / 授权委托书</h3>
      <div style="display:flex; gap:8px; align-items:center">
        <el-select v-model="admissionId" placeholder="选择在院患者" filterable style="width:240px" @change="loadConsents">
          <el-option v-for="a in admissions" :key="a.id" :label="`${a.admissionNo} ${a.patientName}`" :value="a.id" />
        </el-select>
        <el-button type="primary" size="small" :disabled="!admissionId" @click="openCreate">新建同意书</el-button>
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
          <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="patientSign(row)">患者签名</el-button>
          <el-button v-if="row.status === 'PATIENT_SIGNED'" link type="success" size="small" @click="doctorSign(row)">医师签名</el-button>
          <el-button v-if="row.status !== 'REVOKED' && row.status !== 'SIGNED'" link type="danger" size="small" @click="revoke(row)">作废</el-button>
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const typeCn: Record<string, string> = {
  SURGERY: '手术', TRANSFUSION: '输血', ANESTHESIA: '麻醉',
  SPECIAL_EXAM: '特殊检查', SELF_PAY: '自费', PROXY: '授权委托',
}
const statusCn: Record<string, string> = { DRAFT: '待患者签', PATIENT_SIGNED: '待医师签', SIGNED: '已生效', REVOKED: '已作废' }
const statusTag: Record<string, string> = { DRAFT: 'info', PATIENT_SIGNED: 'warning', SIGNED: 'success', REVOKED: 'danger' }

const admissions = ref<Record<string, unknown>[]>([])
const admissionId = ref<number | null>(null)
const consents = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ consentType: 'SURGERY', title: '', content: '', agentName: '', agentRelation: '', agentReason: '' })

async function loadAdmissions() {
  admissions.value = (await client.get('/inpatient/admissions')).data.data
    .filter((a: Record<string, unknown>) => a.status === 'IN_HOSPITAL')
}
async function loadConsents() {
  if (!admissionId.value) { consents.value = []; return }
  consents.value = (await client.get('/emr/consents', { params: { admissionId: admissionId.value } })).data.data
}

function openCreate() {
  Object.assign(form, { consentType: 'SURGERY', title: '', content: '', agentName: '', agentRelation: '', agentReason: '' })
  dialogVisible.value = true
}
function onTypeChange() { /* PROXY 才显示委托人字段，由模板 v-if 控制 */ }

async function create() {
  if (!form.content.trim()) { ElMessage.warning('内容必填'); return }
  saving.value = true
  try {
    await client.post('/emr/consents', { admissionId: admissionId.value, ...form })
    ElMessage.success('已创建（待患者签名）')
    dialogVisible.value = false
    await loadConsents()
  } finally { saving.value = false }
}

async function patientSign(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('患者/委托人签名（录入签名标识）', '患者签名',
    { inputPlaceholder: '如：患者本人 张三' }).catch(() => null)
  if (!res) return
  await client.post(`/emr/consents/${row.id}/patient-sign`, { patientSign: res.value })
  ElMessage.success('患者已签名')
  await loadConsents()
}
async function doctorSign(row: Record<string, unknown>) {
  await client.post(`/emr/consents/${row.id}/doctor-sign`, {})
  ElMessage.success('医师 CA 已签名，同意书生效')
  await loadConsents()
}
async function revoke(row: Record<string, unknown>) {
  await ElMessageBox.confirm('作废该同意书？', '确认', { type: 'warning' }).catch(() => null)
    .then(async (ok) => { if (ok) { await client.put(`/emr/consents/${row.id}/revoke`); ElMessage.success('已作废'); await loadConsents() } })
}

onMounted(loadAdmissions)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
</style>
