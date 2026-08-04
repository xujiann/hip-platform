<template>
  <div class="triage-page">
    <el-card>
      <template #header>预检分诊登记</template>
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名" required><el-input v-model="form.patientName" placeholder="无名氏请编号" /></el-form-item>
        <el-form-item label="分级" required>
          <el-radio-group v-model="form.level">
            <el-radio-button v-for="l in levels" :key="l.value" :value="l.value">{{ l.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="主诉" required>
          <el-input v-model="form.chiefComplaint" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="生命体征">
          <div class="vitals">
            <el-input-number v-model="form.temperature" :min="30" :max="43" :precision="1" :step="0.1" /><span>℃</span>
            <el-input-number v-model="form.pulse" :min="0" :max="250" /><span>次/分</span>
            <el-input-number v-model="form.sbp" :min="0" :max="300" /> /
            <el-input-number v-model="form.dbp" :min="0" :max="200" /><span>mmHg</span>
            <el-input-number v-model="form.spo2" :min="0" :max="100" /><span>%</span>
          </div>
        </el-form-item>
        <el-form-item label="去向">
          <el-select v-model="form.destDeptId" clearable style="width: 200px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-button type="danger" :loading="saving" @click="save">分 诊</el-button>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        分诊队列（按病情分级）
        <el-button link type="primary" style="float: right" @click="load">刷新</el-button>
      </template>
      <el-table :data="records" v-loading="loading" size="small" height="480">
        <el-table-column label="级" width="70">
          <template #default="{ row }">
            <el-tag :type="levelTag[row.level as number]" size="small" effect="dark">{{ ['', 'I', 'II', 'III', 'IV'][row.level as number] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="chiefComplaint" label="主诉" show-overflow-tooltip />
        <el-table-column label="体征" width="150">
          <template #default="{ row }">
            <span v-if="row.sbp">{{ row.sbp }}/{{ row.dbp }} · {{ row.spo2 }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ statusNames[row.status as string] }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button v-if="row.status === 'TRIAGED'" link type="primary" @click="setStatus(row, 'IN_TREATMENT')">开始救治</el-button>
            <el-button v-if="row.status === 'IN_TREATMENT'" link type="warning" @click="setStatus(row, 'ADMITTED')">收住院</el-button>
            <el-button v-if="row.status === 'IN_TREATMENT'" link @click="setStatus(row, 'DISCHARGED')">离院</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const levels = [
  { value: 1, label: 'I 濒危' }, { value: 2, label: 'II 危重' },
  { value: 3, label: 'III 急症' }, { value: 4, label: 'IV 非急症' },
]
const levelTag: Record<number, string> = { 1: 'danger', 2: 'danger', 3: 'warning', 4: 'info' }
const statusNames: Record<string, string> = {
  TRIAGED: '已分诊', IN_TREATMENT: '救治中', ADMITTED: '收住院', DISCHARGED: '已离院',
}

const records = ref<Record<string, unknown>[]>([])
const depts = ref<{ id: number; name: string }[]>([])
const loading = ref(false)
const saving = ref(false)
const form = reactive({
  patientName: '', level: 3, chiefComplaint: '',
  temperature: 36.5, pulse: 80, sbp: 120, dbp: 80, spo2: 98, destDeptId: null as number | null,
})

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/outpatient/triage')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.patientName || !form.chiefComplaint) {
    ElMessage.warning('姓名与主诉必填')
    return
  }
  saving.value = true
  try {
    await client.post('/outpatient/triage', form)
    ElMessage.success('分诊完成')
    form.patientName = ''
    form.chiefComplaint = ''
    await load()
  } finally {
    saving.value = false
  }
}

async function setStatus(row: Record<string, unknown>, status: string) {
  await client.put(`/outpatient/triage/${row.id}/status`, null, { params: { status } })
  await load()
}

onMounted(async () => {
  const resp = await client.get('/system/depts')
  depts.value = resp.data.data.filter((d: { type: string }) => d.type === 'CLINICAL')
  await load()
})
</script>

<style scoped>
.triage-page { display: grid; grid-template-columns: 420px 1fr; gap: 12px; }
.vitals { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
</style>
