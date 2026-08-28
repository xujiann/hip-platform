<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="三管监测" name="lines">
        <el-form inline size="small">
          <el-form-item><el-input v-model="line.admissionId" placeholder="住院ID" style="width: 100px" /></el-form-item>
          <el-form-item>
            <el-select v-model="line.lineType" style="width: 170px">
              <el-option label="呼吸机 VENT" value="VENT" />
              <el-option label="中心静脉导管 CVC" value="CVC" />
              <el-option label="导尿管 URINARY" value="URINARY" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" @click="insertLine" :loading="insertLineLoading">置管登记</el-button>
        </el-form>
        <el-table :data="catheters" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column label="置管" width="110">
            <template #default="{ row }">
              {{ { VENT: '呼吸机', CVC: '中心导管', URINARY: '导尿管' }[row.line_type as string] }}
            </template>
          </el-table-column>
          <el-table-column prop="line_days" label="置管日" width="80" />
          <el-table-column prop="assesses" label="日评估" width="75" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'INFECTED' ? 'danger'
                : row.status === 'ACTIVE' ? 'warning' : 'success'">
                {{ { ACTIVE: '留置中', REMOVED: '已拔管', INFECTED: '已感染' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <template v-if="row.status === 'ACTIVE'">
                <el-button link type="primary" size="small" :loading="busyId === row.id"
                           @click="assess(row)">日评估</el-button>
                <el-button link type="success" size="small" :loading="busyId === row.id"
                           @click="removeLine(row)">拔管</el-button>
                <el-button link type="danger" size="small" :loading="busyId === row.id"
                           @click="infect(row)">判定感染</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="千日感染率" name="rate">
        <el-table :data="rates" size="small" border>
          <el-table-column label="监测类型" width="200">
            <template #default="{ row }">
              {{ { VENT: '呼吸机相关肺炎 VAP', CVC: '导管相关血流感染 CLABSI', URINARY: '导尿管相关尿路感染 CAUTI' }[row.line_type as string] }}
            </template>
          </el-table-column>
          <el-table-column prop="lines" label="置管例数" width="100" />
          <el-table-column prop="line_days" label="置管日数" width="110" />
          <el-table-column prop="infections" label="感染例次" width="100" />
          <el-table-column label="千日感染率‰" width="130">
            <template #default="{ row }">
              <b :style="{ color: Number(row.per_1000_days) > 5 ? '#dc2626' : '#16a34a' }">{{ row.per_1000_days }}</b>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="现患率调查" name="prevalence">
        <el-button type="primary" size="small" @click="loadPrevalence">生成现患率调查表</el-button>
        <el-descriptions v-if="prevalence" :column="4" border size="small" style="margin: 8px 0">
          <el-descriptions-item label="调查日">{{ prevalence.surveyDate }}</el-descriptions-item>
          <el-descriptions-item label="在院患者">{{ prevalence.inHospital }} 人</el-descriptions-item>
          <el-descriptions-item label="现患感染">{{ prevalence.infected }} 人</el-descriptions-item>
          <el-descriptions-item label="现患率">{{ prevalence.prevalenceRate }}%</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="prevalence" :data="prevalence.rows" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="admit_diag_name" label="诊断" show-overflow-tooltip />
          <el-table-column label="院感" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.infected" type="danger" size="small">是</el-tag>
              <span v-else>否</span>
            </template>
          </el-table-column>
          <el-table-column prop="sites" label="感染部位" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

interface Prevalence {
  surveyDate: string
  inHospital: number
  infected: number
  prevalenceRate: number
  rows: Record<string, unknown>[]
}

const tab = ref('lines')
const catheters = ref<Record<string, unknown>[]>([])
const rates = ref<Record<string, unknown>[]>([])
const prevalence = ref<Prevalence | null>(null)
const line = reactive({ admissionId: '', lineType: 'VENT' })
const insertLineLoading = ref(false)
const busyId = ref<unknown>(null)

async function loadLines() { catheters.value = (await client.get('/infection-plus/catheters')).data.data }
async function loadRates() { rates.value = (await client.get('/infection-plus/rate')).data.data }
async function loadPrevalence() { prevalence.value = (await client.get('/infection-plus/prevalence')).data.data }

async function insertLine() {
  if (!line.admissionId) { ElMessage.warning('请填写住院ID'); return }
  insertLineLoading.value = true
  try {
    await client.post('/infection-plus/catheters', { admissionId: Number(line.admissionId), lineType: line.lineType })
    ElMessage.success('已登记，请每日评估')
    await loadLines()
  } finally { insertLineLoading.value = false }
}
async function assess(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('评估记录（是否可拔管）', '日评估', { inputValue: '仍需留置，无感染征象' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.post(`/infection-plus/catheters/${row.id}/assess`, null, { params: { keepLine: true, note: value } })
    await loadLines()
  } finally { busyId.value = null }
}
async function removeLine(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/infection-plus/catheters/${row.id}/remove`)
    ElMessage.success('已拔管')
    await loadLines()
  } finally { busyId.value = null }
}
async function infect(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('病原体（可空）', '判定器械相关感染', { inputValue: '肺炎克雷伯菌' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/infection-plus/catheters/${row.id}/infect`, null, { params: { pathogen: value } })
    ElMessage.warning('已判定感染并同步登记院感病例')
    await loadLines()
  } finally { busyId.value = null }
}

watch(tab, (t) => {
  if (t === 'rate') loadRates()
  if (t === 'prevalence') loadPrevalence()
})
onMounted(loadLines)
</script>
