<template>
  <el-card>
    <div class="toolbar">
      <h3>住院登记 · 在院患者</h3>
      <el-button type="primary" @click="openAdmit">办理入院</el-button>
    </div>
    <el-table :data="admissions" v-loading="loading" border stripe>
      <el-table-column prop="admissionNo" label="住院号" width="170" />
      <el-table-column prop="patientName" label="姓名" width="90" />
      <el-table-column prop="deptName" label="科室" width="110" />
      <el-table-column prop="wardName" label="病区" width="110" />
      <el-table-column prop="bedNo" label="床号" width="70" />
      <el-table-column prop="admitDiagName" label="入院诊断" />
      <el-table-column label="入院时间" width="110">
        <template #default="{ row }">{{ String(row.admitAt).slice(0, 10) }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="办理入院" width="560px">
      <el-form label-width="90px">
        <el-form-item label="患者" required>
          <el-select v-model="form.patientId" filterable remote :remote-method="searchPatients"
                     placeholder="搜索患者（姓名/患者号/证件号）" style="width: 100%">
            <el-option v-for="p in patientOptions" :key="p.id as number"
                       :label="`${p.patientNo} ${p.name}`" :value="p.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="收治科室" required>
          <el-select v-model="form.deptId" style="width: 100%">
            <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="病区" required>
          <el-select v-model="wardId" style="width: 100%" @change="loadBeds">
            <el-option v-for="w in wards" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="床位" required>
          <el-radio-group v-model="form.bedId">
            <el-radio v-for="b in beds" :key="b.id as number" :value="b.id as number" :disabled="b.status !== 'FREE'"
                      border style="margin: 2px">
              {{ b.bedNo }}{{ b.status !== 'FREE' ? `(${b.patientName ?? '占'})` : '' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="入院诊断">
          <el-select v-model="diagCode" filterable remote :remote-method="searchIcd" placeholder="搜索 ICD"
                     style="width: 100%">
            <el-option v-for="i in icdOptions" :key="i.code" :label="`${i.name} (${i.code})`" :value="i.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="预交押金">
          <el-input-number v-model="form.deposit" :min="0" :step="100" style="width: 160px" />
          <el-select v-model="form.payMethod" style="width: 100px; margin-left: 8px">
            <el-option label="现金" value="CASH" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="admit">办理入院</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import type { Admission, Patient } from '../../types/domain'

const admissions = ref<Admission[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const depts = ref<{ id: number; name: string; type: string }[]>([])
const patientOptions = ref<Patient[]>([])
const icdOptions = ref<{ code: string; name: string }[]>([])
const beds = ref<{ id: number; bedNo: string; status: string; patientName?: string }[]>([])
const wardId = ref<number | null>(null)
const diagCode = ref<string | null>(null)

const clinicalDepts = computed(() => depts.value.filter((d) => d.type === 'CLINICAL'))
const wards = computed(() => depts.value.filter((d) => d.type === 'NURSING'))

const form = reactive({
  patientId: null as number | null, deptId: null as number | null, bedId: null as number | null,
  deposit: 1000, payMethod: 'CASH',
})

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/inpatient/admissions')
    admissions.value = resp.data.data
  } finally {
    loading.value = false
  }
}

function openAdmit() {
  form.patientId = null
  form.bedId = null
  diagCode.value = null
  dialogVisible.value = true
}

async function searchPatients(kw: string) {
  const resp = await client.get('/patients', { params: { keyword: kw, page: 0, size: 10 } })
  patientOptions.value = resp.data.data.records
}

async function searchIcd(kw: string) {
  if (!kw) return
  const resp = await client.get('/masterdata/icd10', { params: { keyword: kw } })
  icdOptions.value = resp.data.data
}

async function loadBeds() {
  if (!wardId.value) return
  form.bedId = null
  const resp = await client.get('/inpatient/beds', { params: { wardId: wardId.value } })
  beds.value = resp.data.data
}

async function admit() {
  if (!form.patientId || !form.deptId || !form.bedId) {
    ElMessage.warning('请完整选择患者、科室与床位')
    return
  }
  saving.value = true
  try {
    const icd = icdOptions.value.find((i) => i.code === diagCode.value)
    const resp = await client.post('/inpatient/admissions', {
      ...form, diagIcd: icd?.code ?? null, diagName: icd?.name ?? null,
    })
    ElMessage.success(`入院成功，住院号 ${resp.data.data.admissionNo}`)
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const resp = await client.get('/system/depts')
  depts.value = resp.data.data
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
